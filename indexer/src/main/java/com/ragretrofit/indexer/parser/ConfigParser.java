package com.ragretrofit.indexer.parser;

import com.ragretrofit.indexer.model.CodeChunk;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses XML configuration files (struts-config.xml, web.xml, tiles-defs.xml) 
 * into structured chunks with MVC mapping information.
 */
public class ConfigParser implements CodeParser {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigParser.class);
    
    @Override
    public boolean canParse(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return fileName.equals("web.xml") ||
               fileName.equals("struts-config.xml") ||
               fileName.equals("tiles-defs.xml") ||
               fileName.contains("struts") && fileName.endsWith(".xml");
    }
    
    @Override
    public List<CodeChunk> parse(Path filePath) {
        logger.debug("Parsing config file: {}", filePath);
        
        try {
            String content = IOUtils.toString(new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(content, "", org.jsoup.parser.Parser.xmlParser());
            
            String fileName = filePath.getFileName().toString().toLowerCase();
            
            if (fileName.equals("web.xml")) {
                return parseWebXml(doc, filePath, content);
            } else if (fileName.equals("struts-config.xml")) {
                return parseStrutsConfig(doc, filePath, content);
            } else if (fileName.equals("tiles-defs.xml")) {
                return parseTilesConfig(doc, filePath, content);
            } else {
                return parseGenericXml(doc, filePath, content);
            }
            
        } catch (Exception e) {
            logger.error("Failed to parse config file: " + filePath, e);
            return Collections.emptyList();
        }
    }
    
    private List<CodeChunk> parseWebXml(Document doc, Path filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\\n");
        
        // Parse servlet definitions
        Elements servlets = doc.select("servlet");
        for (Element servlet : servlets) {
            chunks.add(createServletChunk(servlet, filePath, lines));
        }
        
        // Parse servlet mappings
        Elements servletMappings = doc.select("servlet-mapping");
        for (Element mapping : servletMappings) {
            chunks.add(createServletMappingChunk(mapping, filePath, lines));
        }
        
        // Parse filters
        Elements filters = doc.select("filter");
        for (Element filter : filters) {
            chunks.add(createFilterChunk(filter, filePath, lines));
        }
        
        // Parse filter mappings
        Elements filterMappings = doc.select("filter-mapping");
        for (Element mapping : filterMappings) {
            chunks.add(createFilterMappingChunk(mapping, filePath, lines));
        }
        
        return chunks;
    }
    
    private List<CodeChunk> parseStrutsConfig(Document doc, Path filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\\n");
        
        // Parse action mappings
        Elements actions = doc.select("action");
        for (Element action : actions) {
            chunks.add(createActionMappingChunk(action, filePath, lines));
        }
        
        // Parse form beans
        Elements formBeans = doc.select("form-bean");
        for (Element formBean : formBeans) {
            chunks.add(createFormBeanChunk(formBean, filePath, lines));
        }
        
        // Parse forwards
        Elements forwards = doc.select("forward");
        for (Element forward : forwards) {
            chunks.add(createForwardChunk(forward, filePath, lines));
        }
        
        return chunks;
    }
    
    private List<CodeChunk> parseTilesConfig(Document doc, Path filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\\n");
        
        // Parse tile definitions
        Elements definitions = doc.select("definition");
        for (Element definition : definitions) {
            chunks.add(createTileDefinitionChunk(definition, filePath, lines));
        }
        
        return chunks;
    }
    
    private List<CodeChunk> parseGenericXml(Document doc, Path filePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\\n");
        
        // Create a single chunk for the entire config file
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "generic_xml");
        metadata.put("rootElement", doc.root().tagName());
        
        CodeChunk chunk = CodeChunk.builder()
                .id(generateId(filePath.toString(), "CONFIG", 1))
                .content(content)
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString())
                .startLine(1)
                .endLine(lines.length)
                .metadata(metadata)
                .build();
        
        chunks.add(chunk);
        return chunks;
    }
    
    private CodeChunk createServletChunk(Element servlet, Path filePath, String[] lines) {
        String servletName = getElementText(servlet, "servlet-name");
        String servletClass = getElementText(servlet, "servlet-class");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("servletName", servletName);
        mvcMapping.put("servletClass", servletClass);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "servlet");
        metadata.put("servletName", servletName);
        metadata.put("servletClass", servletClass);
        
        // Extract init parameters
        Elements initParams = servlet.select("init-param");
        Map<String, String> initParamsMap = new HashMap<>();
        for (Element param : initParams) {
            String paramName = getElementText(param, "param-name");
            String paramValue = getElementText(param, "param-value");
            initParamsMap.put(paramName, paramValue);
        }
        if (!initParamsMap.isEmpty()) {
            metadata.put("initParams", initParamsMap);
        }
        
        int estimatedLine = estimateLineNumber(servlet.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "SERVLET", estimatedLine))
                .content(servlet.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#servlet#" + servletName)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(servlet.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createServletMappingChunk(Element mapping, Path filePath, String[] lines) {
        String servletName = getElementText(mapping, "servlet-name");
        String urlPattern = getElementText(mapping, "url-pattern");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("servletName", servletName);
        mvcMapping.put("urlPattern", urlPattern);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "servlet-mapping");
        metadata.put("servletName", servletName);
        metadata.put("urlPattern", urlPattern);
        
        int estimatedLine = estimateLineNumber(mapping.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "SERVLET_MAPPING", estimatedLine))
                .content(mapping.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#servlet-mapping#" + servletName)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(mapping.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createActionMappingChunk(Element action, Path filePath, String[] lines) {
        String path = action.attr("path");
        String type = action.attr("type");
        String name = action.attr("name");
        String scope = action.attr("scope");
        String input = action.attr("input");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("actionPath", path);
        mvcMapping.put("actionClass", type);
        mvcMapping.put("formBean", name);
        mvcMapping.put("inputPage", input);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "action-mapping");
        metadata.put("path", path);
        metadata.put("type", type);
        metadata.put("name", name);
        metadata.put("scope", scope);
        
        // Extract forwards
        Elements forwards = action.select("forward");
        List<Map<String, String>> forwardsList = new ArrayList<>();
        for (Element forward : forwards) {
            Map<String, String> forwardMap = new HashMap<>();
            forwardMap.put("name", forward.attr("name"));
            forwardMap.put("path", forward.attr("path"));
            forwardsList.add(forwardMap);
        }
        if (!forwardsList.isEmpty()) {
            metadata.put("forwards", forwardsList);
        }
        
        int estimatedLine = estimateLineNumber(action.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "ACTION", estimatedLine))
                .content(action.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#action#" + path)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(action.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createFormBeanChunk(Element formBean, Path filePath, String[] lines) {
        String name = formBean.attr("name");
        String type = formBean.attr("type");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("formBeanName", name);
        mvcMapping.put("formBeanClass", type);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "form-bean");
        metadata.put("name", name);
        metadata.put("type", type);
        
        int estimatedLine = estimateLineNumber(formBean.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "FORM_BEAN", estimatedLine))
                .content(formBean.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#form-bean#" + name)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(formBean.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createFilterChunk(Element filter, Path filePath, String[] lines) {
        String filterName = getElementText(filter, "filter-name");
        String filterClass = getElementText(filter, "filter-class");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("filterName", filterName);
        mvcMapping.put("filterClass", filterClass);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "filter");
        metadata.put("filterName", filterName);
        metadata.put("filterClass", filterClass);
        
        int estimatedLine = estimateLineNumber(filter.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "FILTER", estimatedLine))
                .content(filter.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#filter#" + filterName)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(filter.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createFilterMappingChunk(Element mapping, Path filePath, String[] lines) {
        String filterName = getElementText(mapping, "filter-name");
        String urlPattern = getElementText(mapping, "url-pattern");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("filterName", filterName);
        mvcMapping.put("urlPattern", urlPattern);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "filter-mapping");
        metadata.put("filterName", filterName);
        metadata.put("urlPattern", urlPattern);
        
        int estimatedLine = estimateLineNumber(mapping.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "FILTER_MAPPING", estimatedLine))
                .content(mapping.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#filter-mapping#" + filterName)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(mapping.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createForwardChunk(Element forward, Path filePath, String[] lines) {
        String name = forward.attr("name");
        String path = forward.attr("path");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("forwardName", name);
        mvcMapping.put("forwardPath", path);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "forward");
        metadata.put("name", name);
        metadata.put("path", path);
        
        int estimatedLine = estimateLineNumber(forward.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "FORWARD", estimatedLine))
                .content(forward.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#forward#" + name)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(forward.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createTileDefinitionChunk(Element definition, Path filePath, String[] lines) {
        String name = definition.attr("name");
        String template = definition.attr("template");
        String extend = definition.attr("extends");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("tileName", name);
        mvcMapping.put("template", template);
        if (!extend.isEmpty()) {
            mvcMapping.put("extends", extend);
        }
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("configType", "tile-definition");
        metadata.put("name", name);
        metadata.put("template", template);
        if (!extend.isEmpty()) {
            metadata.put("extends", extend);
        }
        
        // Extract put elements
        Elements puts = definition.select("put");
        Map<String, String> attributes = new HashMap<>();
        for (Element put : puts) {
            attributes.put(put.attr("name"), put.attr("value"));
        }
        if (!attributes.isEmpty()) {
            metadata.put("attributes", attributes);
        }
        
        int estimatedLine = estimateLineNumber(definition.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "TILE_DEF", estimatedLine))
                .content(definition.outerHtml())
                .type(CodeChunk.ChunkType.CONFIG_SECTION)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#tile#" + name)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(definition.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    // Helper methods
    private String getElementText(Element parent, String selector) {
        Element element = parent.selectFirst(selector);
        return element != null ? element.text() : "";
    }
    
    private int countLines(String text) {
        return (int) text.chars().filter(c -> c == '\n').count() + 1;
    }
    
    private int estimateLineNumber(String elementHtml, Path filePath) {
        // This is a rough estimation - in real implementation you'd want to preserve line numbers
        return Math.abs(elementHtml.hashCode() % 1000) + 1;
    }
    
    private String generateId(String filePath, String type, int line) {
        return filePath + "#" + type + "#" + line + "#" + System.currentTimeMillis();
    }
    
    @Override
    public int getPriority() {
        return 10; // Higher priority for config files
    }
}