package pro.belbix.ethparser.web3.deployer.db;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import pro.belbix.ethparser.dto.v0.DeployerDTO;
import pro.belbix.ethparser.properties.AppProperties;
import pro.belbix.ethparser.repositories.v0.DeployerRepository;
import pro.belbix.ethparser.web3.contracts.ContractUtils;

@Service
@Log4j2
public class DeployerDbService {

  private final DeployerRepository deployerRepository;
  private final AppProperties appProperties;

  public DeployerDbService(DeployerRepository deployerRepository, AppProperties appProperties) {
    this.deployerRepository = deployerRepository;
    this.appProperties = appProperties;
  }

  public boolean save(DeployerDTO dto) {
    if (!appProperties.isOverrideDuplicates() && deployerRepository.existsById(dto.getId())) {
      log.info("Duplicate Deployer entry " + dto.getId());
      return false;
    }
    deployerRepository.saveAndFlush(dto);
    return true;
  }

  public Integer getLastBlock(String network) {
    DeployerDTO dto = deployerRepository.findFirstByNetworkOrderByBlockDesc(network);
    log.info("Last deployer dto {}", dto);
    if (dto == null) {
      return ContractUtils.getStartBlock(network);
    }
    return (int) dto.getBlock();
  }
}
