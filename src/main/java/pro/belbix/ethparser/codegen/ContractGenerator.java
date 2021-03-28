package pro.belbix.ethparser.codegen;

import static pro.belbix.ethparser.web3.MethodDecoder.extractLogIndexedValues;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.ObjectMapperFactory;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.response.AbiDefinition;
import org.web3j.protocol.core.methods.response.EthLog.LogResult;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.tx.Contract;
import org.web3j.utils.Strings;
import pro.belbix.ethparser.properties.AppProperties;
import pro.belbix.ethparser.service.EtherscanService;
import pro.belbix.ethparser.web3.MethodDecoder;
import pro.belbix.ethparser.web3.Web3Service;
import pro.belbix.ethparser.web3.abi.WrapperReader;
import pro.belbix.ethparser.web3.contracts.ContractLoader;

@Service
@Log4j2
public class ContractGenerator {

    public final static Credentials STUB_CREDENTIALS =
        Credentials.create("8da4ef21b864d2cc526dbdb2a120bd2874c36c9d0a1fb7f8c63d7f7a8b41de8f");
    private final EtherscanService etherscanService = new EtherscanService();
    private final Map<String, String> contractToWrapperName = new HashMap<>();
    private final Map<String, Class<?>> wrapperNameToClass = new HashMap<>();
    private final Map<String, Event> eventsMap = new HashMap<>();
    private final Map<String, URLClassLoader> classLoaders = new HashMap<>();
    private final Set<String> parsedContractEvents = new HashSet<>();

    private final AppProperties appProperties;
    private final ContractLoader contractLoader;
    private final Web3Service web3Service;

    @Value("${contract-generator.contract:}")
    private String contract;
    @Value("${contract-generator.category:}")
    private String category;
    @Value("${contract-generator.rootPackage:generated}")
    private String rootPackage;
    @Value("${contract-generator.destDir:./tmp}")
    private String destDir;

    private Instant lastCall = Instant.now();

    public ContractGenerator(AppProperties appProperties,
        ContractLoader contractLoader, Web3Service web3Service) {
        this.appProperties = appProperties;
        this.contractLoader = contractLoader;
        this.web3Service = web3Service;
    }

    @Deprecated // maybe will use, let's see on performance
    public void start() {
        log.info("Start Contract Generator {} {} {} {}",
            contract, category, rootPackage, destDir);
//        if (contract != null && !contract.isBlank()) {
//            generateFromAddress(contract, destDir, category);
//        }
//        contractLoader.load();
//        ContractUtils.getAllVaultAddresses()
//            .forEach(address ->
//                generateFromAddress(address, destDir, rootPackage + ".harvest"));
//
//        ContractUtils.getAllPoolAddresses()
//            .forEach(address ->
//                generateFromAddress(address, destDir, rootPackage + ".harvest"));
//
//        ContractUtils.getAllUniPairs().stream()
//            .map(u -> u.getContract().getAddress())
//            .forEach(address ->
//                generateFromAddress(address, destDir, rootPackage + ".uniswap"));
//
//        ContractUtils.getAllTokens().stream()
//            .map(u -> u.getContract().getAddress())
//            .forEach(address ->
//                generateFromAddress(address, destDir, rootPackage + ".erc20"));
//        generateMappingClass();
    }

    public Class<?> getWrapperClassByAddress(String address, Integer block) {
        String wrapperName = contractToWrapperName.get(address);
        if (wrapperName == null) {
            wrapperName = generateFromAddress(address, destDir, rootPackage, block);
        }
        if(wrapperName == null) {
            return null;
        }
        Class<?> clazz = wrapperNameToClass.get(wrapperName);
        if (clazz != null) {
            return clazz;
        }
        clazz = loadFromFile(destDir, wrapperName);
        wrapperNameToClass.put(wrapperName, clazz);
        return clazz;
    }

    public Class<?> loadFromFile(String dir, String fullName) {
        try {
            String javaFile = dir + File.separator
                + fullName.replace(".", File.separator) + ".java";

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            compiler.run(null, null, null, javaFile);

            URLClassLoader classLoader = classLoaders.get(dir);
            if (classLoader == null) {
                classLoader = new URLClassLoader(
                    new URL[]{new File(dir).toURI().toURL()});
                classLoaders.put(dir, classLoader);
            }

            return classLoader.loadClass(fullName);
        } catch (Throwable e) {
            log.error("Error load class {} from {}", fullName, dir, e);
            return null;
        }
    }

    String generateFromAddress(String address, String dir, String pkg, Integer block) {
        try {

            // etherscan throttling
            long diff = Duration.between(lastCall, Instant.now()).toMillis();
            if (diff < 200) {
                Thread.sleep(200 - diff);
            }
            EtherscanService.SourceCodeResult sourceCode =
                etherscanService.contractSourceCode(address, appProperties.getEtherscanApiKey());
            if (sourceCode == null) {
                return null;
            }

            List<AbiDefinition> abiDefinitions = abiToDefinition(sourceCode.getAbi());

            String className = generate(
                abiDefinitions,
                new File(dir),
                sourceCode.getContractName(),
                pkg
            );
            contractToWrapperName.put(address, className);
            lastCall = Instant.now();
            if (isProxy(abiDefinitions)) {
                log.info("Detected proxy contract, parse implementation");
                String implAddress = readProxyAddress(address, dir, className, block);
                if (implAddress != null) {
                    String implClassName = generateFromAddress(implAddress, dir, pkg, block);
                    contractToWrapperName.put(address, implClassName);
                    return implClassName;
                } else {
                    log.error("Can't fetch implementation for proxy {}", address);
                }
            }
            return className;
        } catch (Exception e) {
            log.error("Error while generate contract for address {}", address, e);
            return null;
        }
    }

    private boolean isProxy(List<AbiDefinition> abiDefinitions) {
        for (AbiDefinition abiDefinition : abiDefinitions) {
            if ("implementation".equals(abiDefinition.getName())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String readProxyAddress(
        String proxyAddress,
        String dir,
        String className,
        Integer block) {

        try {
            Class<?> clazz = loadFromFile(dir, className);
            if (clazz == null) {
                return null;
            }

            // open zeppelin proxy doesn't have public call_implementation
            String impAddress = findLastProxyUpgrade(proxyAddress, clazz, block);
            if (impAddress != null) {
                return impAddress;
            }

            // EIP-897 DelegateProxy concept
            Method method = clazz.getDeclaredMethod("call_implementation");
            Object proxyInstance =
                WrapperReader.createWrapperInstance(clazz, proxyAddress, web3Service.getWeb3());
            RemoteFunctionCall<String> call =
                (RemoteFunctionCall<String>) method.invoke(proxyInstance);
            WrapperReader.call("call_implementation",
                clazz, proxyAddress, web3Service.getWeb3(), block);
            return call.send();
        } catch (Exception e) {
            log.error("Error load generated class {}", className, e);
            return null;
        }
    }

    private String findLastProxyUpgrade(String address, Class<?> clazz, Integer block) {
        Event event = WrapperReader.extractEvent("UPGRADED_EVENT", clazz);
        if (event == null) {
            return null;
        }
        String methodHex =
            MethodDecoder.createMethodFullHex(event.getName(), event.getParameters());
        List<LogResult> logResults = web3Service.fetchContractLogs(
            List.of(address),
            null,
            block,
            methodHex);
        if (logResults == null || logResults.isEmpty()) {
            return null;
        }
        Log ethLog = (Log) logResults.get(logResults.size() - 1).get();
        List<Type> types = extractLogIndexedValues(
            ethLog.getTopics(), ethLog.getData(), event.getParameters());
        if (types == null || types.isEmpty()) {
            log.error("Empty types for {}", ethLog);
            return null;
        }
        return (String) types.get(0).getValue();
    }

    private String generate(
        List<AbiDefinition> functionDefinitions,
        File destinationDir,
        String contractName,
        String basePackageName
    ) {
        String className = Strings.capitaliseFirstLetter(contractName)
            + "_" + abiHashCodePostfix(functionDefinitions);
        log.info("Generating " + basePackageName + "." + className + " ... ");

        try {
            new SolidityFunctionWrapper(
                true,
                false,
                true,
                Address.DEFAULT_LENGTH / Byte.SIZE)
                .generateJavaFiles(
                    Contract.class,
                    className,
                    Contract.BIN_NOT_PROVIDED,
                    functionDefinitions,
                    destinationDir.toString(),
                    basePackageName,
                    null);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return basePackageName + "." + className;
    }

    static String abiHashCodePostfix(List<AbiDefinition> functionDefinitions) {
        StringBuilder sb = new StringBuilder();
        for (AbiDefinition abiDefinition : functionDefinitions) {
            sb.append(abiDefinition.hashCode());
        }
        return (sb.toString().hashCode() + "").replace("-", "0");
    }

    public static List<AbiDefinition> abiToDefinition(String abi) {
        ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();
        AbiDefinition[] abiDefinition;
        try {
            abiDefinition = objectMapper.readValue(abi, AbiDefinition[].class);
        } catch (IOException e) {
            log.error("abiToDefinition error for: {}", abi);
            throw new RuntimeException(e);
        }
        return Arrays.asList(abiDefinition);
    }

    private void generateMappingClass() {
        try {
            List<Object> values = new ArrayList<>();
            values.add(ContractGenerator.class);
            StringBuilder sbFormat = new StringBuilder();
            contractToWrapperName.forEach((contract, name) -> {
                values.add(contract);
                values.add(ClassName.bestGuess(name));
                sbFormat.append("$S, $T.class").append(",\n");
            });
            sbFormat.setLength(sbFormat.length() - 2);
            sbFormat.append("\n");

            TypeSpec.Builder classBuilder = TypeSpec.classBuilder("WrapperMapper")
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("<p>Auto generated code.\n"
                    + "<p><strong>Do not modify!</strong>\n")
                .addField(FieldSpec.builder(ParameterizedTypeName.get(
                    ClassName.get(Map.class),
                    ClassName.get(String.class),
                    ParameterizedTypeName.get(
                        ClassName.get(Class.class),
                        WildcardTypeName.subtypeOf(Object.class)
                    )
                ), "contractToWrapper")
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL, Modifier.STATIC)
                    .initializer("$T.createMap(\n"
                        + sbFormat.toString()
                        + ")", values.toArray(new Object[0]))
                    .build());

            JavaFile javaFile = JavaFile.builder(rootPackage, classBuilder.build())
                .indent("    ")
                .skipJavaLangImports(true)
                .build();

            javaFile.writeTo(new File(destDir));
        } catch (Exception e) {
            log.error("Error generate map class", e);
        }
    }

    public static Map<String, Class<?>> createMap(Object... values) {
        if (values.length == 0) {
            return Map.of();
        }
        if (values.length % 2 != 0) {
            throw new IllegalStateException("Wrong strings: " + Arrays.toString(values));
        }
        Map<String, Class<?>> result = new HashMap<>();
        for (int i = 0; i < values.length; i = i + 2) {
            result.put((String) values[i], (Class<?>) values[i + 1]);
        }
        return result;
    }

    public Event findEventByHex(String contractAddress, String methodHash, Integer block) {
        Event event = eventsMap.get(methodHash);
        if (event != null) {
            return event;
        }
        if (parsedContractEvents.contains(contractAddress)) {
            log.warn("Event {} not found for contract {}", methodHash, contractAddress);
            return null;
        }
        parsedContractEvents.add(contractAddress);
        Class<?> clazz = getWrapperClassByAddress(contractAddress, block);
        if (clazz == null) {
            log.warn("Not found class for {} {}", contractAddress, methodHash);
            return null;
        }
        addEventsToMap(clazz);
        return eventsMap.get(methodHash);
    }

    private void addEventsToMap(Class<?> clazz) {
        for (Event event : WrapperReader.collectEvents(clazz)) {
            String methodHex = MethodDecoder
                .createMethodFullHex(event.getName(), event.getParameters());
            eventsMap.put(methodHex, event);
        }
    }
}