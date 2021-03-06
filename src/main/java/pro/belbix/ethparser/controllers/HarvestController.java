package pro.belbix.ethparser.controllers;

import static pro.belbix.ethparser.service.AbiProviderService.ETH_NETWORK;
import static pro.belbix.ethparser.utils.CommonUtils.parseLong;
import static pro.belbix.ethparser.utils.CommonUtils.reduceListElements;

import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.util.Strings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.web3j.protocol.ObjectMapperFactory;
import pro.belbix.ethparser.dto.v0.HarvestDTO;
import pro.belbix.ethparser.model.PaginatedResponse;
import pro.belbix.ethparser.model.RestResponse;
import pro.belbix.ethparser.repositories.v0.HarvestRepository;
import pro.belbix.ethparser.repositories.v0.HarvestRepository.UserBalance;
import pro.belbix.ethparser.service.DtoCache;
import pro.belbix.ethparser.web3.contracts.ContractType;
import pro.belbix.ethparser.web3.contracts.db.ContractDbService;
import pro.belbix.ethparser.web3.harvest.db.VaultActionsDBService;

@ConditionalOnExpression("!${ethparser.onlyParse:false}")
@RestController
@Log4j2
public class HarvestController {

    private final HarvestRepository harvestRepository;
    private final VaultActionsDBService vaultActionsDBService;
    private final ContractDbService contractDbService;
    private final DtoCache dtoCache;

    public HarvestController(HarvestRepository harvestRepository,
        VaultActionsDBService vaultActionsDBService,
        ContractDbService contractDbService, DtoCache dtoCache) {
        this.harvestRepository = harvestRepository;
        this.vaultActionsDBService = vaultActionsDBService;
        this.contractDbService = contractDbService;
        this.dtoCache = dtoCache;
    }

    @RequestMapping(value = "api/transactions/last/harvest", method = RequestMethod.GET)
    public List<HarvestDTO> fetchLatest(
        @RequestParam(value = "network", required = false, defaultValue = ETH_NETWORK) String network
    ) {
        return harvestRepository.fetchLatest(network);
    }

    @RequestMapping(value = "api/transactions/history/harvest/{name}", method = RequestMethod.GET)
    public Iterable<HarvestDTO> harvestHistoryDataForVault(
        @PathVariable("name") String _address,
        @RequestParam(value = "reduce", required = false, defaultValue = "1") Integer reduce,
        @RequestParam(value = "start", required = false, defaultValue = "0") Long start,
        @RequestParam(value = "end", required = false, defaultValue = Long.MAX_VALUE + "") Long end,
        @RequestParam(value = "network", required = false, defaultValue = ETH_NETWORK) String network
    ) {
        String address;
        if (!_address.startsWith("0x")) {
            address = contractDbService.getAddressByName(_address, ContractType.VAULT, network)
                .orElseThrow();
        } else {
            address = _address;
        }
        return reduceListElements(
            dtoCache.load("findAllByVaultOrderByBlockDate" +
                address + start + end + network, () ->
                harvestRepository.findAllByVaultOrderByBlockDate(
                    address,
                    start,
                    end,
                    network)), reduce);
    }

    @RequestMapping(value = "api/transactions/history/harvest", method = RequestMethod.GET)
    public Iterable<HarvestDTO> harvestHistoryData(
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "reduce", required = false, defaultValue = "1") Integer reduce,
        @RequestParam(value = "network", required = false, defaultValue = ETH_NETWORK) String network
    ) {
        return reduceListElements(
            dtoCache.load("findAllByVaultOrderByBlockDate" +
                from + to + network, () ->
                vaultActionsDBService.fetchHarvest(from, to, network)), reduce);
    }

    @GetMapping("/history/harvest/{address}")
    public List<HarvestDTO> addressHistoryHarvest(
        @PathVariable("address") String address,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "network", required = false, defaultValue = ETH_NETWORK) String network
    ) {
        return harvestRepository.fetchAllByOwner(address.toLowerCase(), parseLong(from, 0),
            parseLong(to, Long.MAX_VALUE), network);
    }

    @GetMapping("/user_balances")
    public List<UserBalance> userBalances(
        @RequestParam(value = "network", required = false, defaultValue = ETH_NETWORK) String network
    ) {
        return harvestRepository.fetchOwnerBalances(network);
    }

    @GetMapping(value = "/harvest/pages")
    public RestResponse harvestPages(
        @RequestParam("pageSize") String pageSize,
        @RequestParam("page") String page,
        @RequestParam(value = "ordering", required = false) String ordering,
        @RequestParam(value = "vault", required = false) String vault,
        @RequestParam(value = "minAmount", required = false) Integer minAmount,
        @RequestParam(value = "network", required = false, defaultValue = ETH_NETWORK) String network
    ) {
        try {
            int start = Integer.parseInt(page);
            int size = Integer.parseInt(pageSize);
            Sort sorting = Sort.by("blockDate");
            if (!Strings.isBlank(ordering) && "desc".equals(ordering)) {
                sorting = sorting.descending();
            }

            Page<HarvestDTO> pages;
            if (minAmount == null) {
                minAmount = Integer.MIN_VALUE;
            }
            if (Strings.isBlank(vault)) {
                pages = harvestRepository
                    .fetchPages(minAmount, network, PageRequest.of(start, size, sorting));
            } else {
                if (!vault.startsWith("0x")) {
                    vault = contractDbService.getAddressByName(vault, ContractType.VAULT, network)
                        .orElseThrow();
                }
                pages = harvestRepository
                    .fetchPagesByVault(vault, network, minAmount,
                        PageRequest.of(start, size, sorting));
            }

            if (!pages.hasContent()) {
                return RestResponse.error("Data not found");
            }
            return RestResponse.ok(
                ObjectMapperFactory.getObjectMapper().writeValueAsString(
                    PaginatedResponse.builder()
                        .currentPage(start)
                        .previousPage(pages.hasPrevious() ? start - 1 : -1)
                        .nextPage(pages.hasNext() ? start + 1 : -1)
                        .totalPages(pages.getTotalPages())
                        .data(pages.getContent())
                        .build()
                )
            );

        } catch (Exception e) {
            String msg = "Error get hardwork pages";
            log.warn(msg, e);
            return RestResponse.error(msg);
        }
    }

}
