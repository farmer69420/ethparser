package pro.belbix.ethparser.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import pro.belbix.ethparser.Application;
import pro.belbix.ethparser.repositories.v0.HarvestTvlRepository;
import pro.belbix.ethparser.service.HarvestTvlDBService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pro.belbix.ethparser.service.AbiProviderService.ETH_NETWORK;

@SpringBootTest(classes = Application.class)
@ContextConfiguration
@AutoConfigureMockMvc
public class TvlControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HarvestTvlRepository harvestTvlRepository;

    @Autowired
    private HarvestTvlDBService harvestTvlDBService;

    @ParameterizedTest
    @ValueSource(strings = {
            "0x5d9d25c7C457dD82fc8668FFC6B9746b674d4EcB",
            "0xf0358e8c3CD5Fa238a29301d0bEa3D63A17bEdBE"})
    public void transactionsHistoryTVLAddress(String address) throws Exception {

        String expectedResult = objectMapper.writeValueAsString(harvestTvlDBService
                .fetchTvlByVault(address, 0, Long.MAX_VALUE, ETH_NETWORK));

        this.mockMvc.perform(get("/api/transactions/history/tvl/" + address))
                .andExpect(status().isOk())
                .andExpect(content().string(expectedResult));
    }

    @Disabled("Need to be fixed in the scope of https://github.com/harvestfi/ethparser/issues/92")
    @ParameterizedTest(name = "{index} => name={0}, address={1}")
    @CsvSource({
            "USDC, 0xf0358e8c3CD5Fa238a29301d0bEa3D63A17bEdBE",
            "WBTC, 0x5d9d25c7C457dD82fc8668FFC6B9746b674d4EcB",
    })
    public void transactionsHistoryTVLName(String name, String address) throws Exception {

        String expectedResult = objectMapper.writeValueAsString(harvestTvlDBService
                .fetchTvlByVault(address, 0, Long.MAX_VALUE, ETH_NETWORK));

        this.mockMvc.perform(get("/api/transactions/history/tvl/" + name))
                .andExpect(status().isOk())
                .andExpect(content().string(expectedResult));
    }

    @Test
    public void transactionsHistoryAllTVL() throws Exception {
        String expectedResult = objectMapper.writeValueAsString(
                harvestTvlRepository
                        .getHistoryOfAllTvl(0, Long.MAX_VALUE, ETH_NETWORK));

        this.mockMvc.perform(get("/api/transactions/history/alltvl"))
                .andExpect(status().isOk())
                .andExpect(content().string(expectedResult));
    }

}
