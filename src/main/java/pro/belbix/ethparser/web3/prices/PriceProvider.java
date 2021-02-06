package pro.belbix.ethparser.web3.prices;

import static java.util.Objects.requireNonNullElse;
import static pro.belbix.ethparser.utils.Caller.silentCall;
import static pro.belbix.ethparser.web3.FunctionsNames.TOTAL_SUPPLY;
import static pro.belbix.ethparser.web3.MethodDecoder.parseAmount;
import static pro.belbix.ethparser.web3.contracts.Tokens.isStableCoin;
import static pro.belbix.ethparser.web3.contracts.Tokens.simplifyName;
import static pro.belbix.ethparser.web3.contracts.LpContracts.isDivisionSequenceSecondDividesFirst;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.web3j.tuples.generated.Tuple2;
import pro.belbix.ethparser.dto.PriceDTO;
import pro.belbix.ethparser.repositories.PriceRepository;
import pro.belbix.ethparser.utils.Caller;
import pro.belbix.ethparser.web3.contracts.ContractUtils;
import pro.belbix.ethparser.web3.FunctionsUtils;
import pro.belbix.ethparser.web3.contracts.TokenInfo;
import pro.belbix.ethparser.web3.contracts.Tokens;
import pro.belbix.ethparser.web3.contracts.LpContracts;

@Service
@Log4j2
public class PriceProvider {

    private final Map<String, TreeMap<Long, Double>> lastPrices = new HashMap<>();
    private long updateBlockDifference = 0;
    private final Pageable limitOne = PageRequest.of(0, 1);

    private final FunctionsUtils functionsUtils;
    private final PriceRepository priceRepository;

    public PriceProvider(FunctionsUtils functionsUtils, PriceRepository priceRepository) {
        this.functionsUtils = functionsUtils;
        this.priceRepository = priceRepository;
    }

    public void setUpdateBlockDifference(long updateBlockDifference) {
        this.updateBlockDifference = updateBlockDifference;
    }

    public double getLpPositionAmountInUsd(String lpAddress, double amount, long block) {
        Tuple2<String, String> names = LpContracts.lpHashToCoinNames.get(lpAddress);
        if (names == null) {
            throw new IllegalStateException("Not found names for " + lpAddress);
        }
        Tuple2<Double, Double> usdPrices = new Tuple2<>(
            getPriceForCoin(names.component1(), block),
            getPriceForCoin(names.component2(), block)
        );
        Tuple2<Double, Double> lpUnderlyingBalances = functionsUtils.callReserves(lpAddress, block);
        if (lpUnderlyingBalances == null) {
            throw new IllegalStateException("Can't reach reserves for " + lpAddress);
        }
        double lpBalance = parseAmount(
            functionsUtils.callIntByName(TOTAL_SUPPLY, lpAddress, block)
                .orElseThrow(() -> new IllegalStateException("Error get supply from " + lpAddress)),
            lpAddress);
        double positionFraction = amount / lpBalance;

        double firstCoin = positionFraction * lpUnderlyingBalances.component1();
        double secondCoin = positionFraction * lpUnderlyingBalances.component2();

        double firstVaultUsdAmount = firstCoin * usdPrices.component1();
        double secondVaultUsdAmount = secondCoin * usdPrices.component2();
        return firstVaultUsdAmount + secondVaultUsdAmount;
    }

    // you can use Vault name instead of coinName if it is not a LP
    public Double getPriceForCoin(String coinName, long block) {
        String coinNameSimple = simplifyName(coinName);
        updateUSDPrice(coinNameSimple, block);
        if (isStableCoin(coinNameSimple)) {
            return 1.0;
        }
        return getLastPrice(coinNameSimple, block);
    }

    public Tuple2<Double, Double> getPairPriceForStrategyHash(String strategyHash, Long block) {
        return getPairPriceForLpHash(ContractUtils.vaultUnderlyingToken(strategyHash), block);
    }

    public Tuple2<Double, Double> getPairPriceForLpHash(String lpHash, Long block) {
        Tuple2<String, String> names = LpContracts.lpHashToCoinNames.get(lpHash);
        if (names == null) {
            throw new IllegalStateException("Not found names for " + lpHash);
        }
        return new Tuple2<>(
            getPriceForCoin(names.component1(), block),
            getPriceForCoin(names.component2(), block)
        );
    }

    private void updateUSDPrice(String coinName, long block) {
        if (!Tokens.isCreated(coinName, (int) block)) {
            savePrice(0.0, coinName, block);
            return;
        }
        if (isStableCoin(coinName)) {
            // todo parse stablecoin prices
            return;
        }

        if (hasFreshPrice(coinName, block)) {
            return;
        }

        double price = getPriceForCoinWithoutCache(coinName, block);

        savePrice(price, coinName, block);
    }

    private double getPriceForCoinWithoutCache(String name, Long block) {
        TokenInfo tokenInfo = Tokens.getTokenInfo(name);
        String lpName = tokenInfo.findLp(block).component1();
        PriceDTO priceDTO = silentCall(() -> priceRepository.fetchLastPrice(lpName, block, limitOne))
            .filter(Caller::isFilledList)
            .map(l -> l.get(0))
            .orElse(null);
        if (priceDTO == null) {
            log.warn("Saved price not found for " + name + " at block " + block);
            return getPriceForCoinWithoutCacheOld(name, block);
        }
        if (!priceDTO.getToken().equalsIgnoreCase(name)
            && !priceDTO.getOtherToken().equalsIgnoreCase(name)) {
            throw new IllegalStateException("Wrong source for " + name);
        }
        if (block - priceDTO.getBlock() > 1000) {
            log.warn("Price have not updated more then {} for {}", block - priceDTO.getBlock(), name);
        }

        double otherTokenPrice = getPriceForCoin(priceDTO.getOtherToken(), block);
        return priceDTO.getPrice() * otherTokenPrice;
    }

    @Deprecated
    private double getPriceForCoinWithoutCacheOld(String name, Long block) {
        TokenInfo tokenInfo = Tokens.getTokenInfo(name);
        String lpName = tokenInfo.findLp(block).component1();
        String otherTokenName = tokenInfo.findLp(block).component2();
        String lpHash = LpContracts.lpNameToHash.get(lpName);
        if (lpHash == null) {
            throw new IllegalStateException("Not found hash for " + tokenInfo);
        }

        Tuple2<Double, Double> reserves = functionsUtils.callReserves(lpHash, block);
        if (reserves == null) {
            throw new IllegalStateException("Can't reach reserves for " + tokenInfo);
        }
        double price;
        if (isDivisionSequenceSecondDividesFirst(lpHash)) {
            price = reserves.component2() / reserves.component1();
        } else {
            price = reserves.component1() / reserves.component2();
        }

        price *= getPriceForCoin(otherTokenName, block);
        log.info("Price {} fetched {} on block {}", name, price, block);
        return price;
    }

    private boolean hasFreshPrice(String name, long block) {
        TreeMap<Long, Double> lastPriceByBlock = lastPrices.get(name);
        if (lastPriceByBlock == null) {
            return false;
        }

        Entry<Long, Double> entry = lastPriceByBlock.floorEntry(block);
        if (entry == null || Math.abs(entry.getKey() - block) >= updateBlockDifference) {
            return false;
        }
        return entry.getValue() != null && entry.getValue() != 0;
    }

    private void savePrice(double price, String name, long block) {
        TreeMap<Long, Double> lastPriceByBlock = lastPrices.computeIfAbsent(name, k -> new TreeMap<>());
        lastPriceByBlock.put(block, price);
    }

    private double getLastPrice(String name, long block) {
        TreeMap<Long, Double> lastPriceByBlocks = lastPrices.get(name);
        if (lastPriceByBlocks == null) {
            return 0.0;
        }
        Entry<Long, Double> entry = lastPriceByBlocks.floorEntry(requireNonNullElse(block, 0L));
        if (entry != null && entry.getValue() != null) {
            return entry.getValue();
        }
        return 0.0;
    }

}
