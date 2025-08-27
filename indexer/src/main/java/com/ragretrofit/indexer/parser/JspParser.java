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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses JSP files into structured chunks with MVC mapping information.
 * Extracts Struts tags, includes, and scriptlet sections.
 */
public class JspParser implements CodeParser {
    
    private static final Logger logger = LoggerFactory.getLogger(JspParser.class);
    
    // Regex patterns for JSP elements
    private static final Pattern SCRIPTLET_PATTERN = Pattern.compile("<%([^%]|%[^>])*%>", Pattern.DOTALL);
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("<%=([^%]|%[^>])*%>", Pattern.DOTALL);
    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile("<%@([^%]|%[^>])*%>", Pattern.DOTALL);
    private static final Pattern STRUTS_TAG_PATTERN = Pattern.compile("<(html:|logic:|bean:)([^>]+)>", Pattern.DOTALL);
    
    @Override
    public boolean canParse(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return fileName.endsWith(".jsp") || fileName.endsWith(".jspx");
    }
    
    @Override
    public List<CodeChunk> parse(Path filePath) {
        logger.debug("Parsing JSP file: {}", filePath);
        
        try {
            String content = IOUtils.toString(new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8);
            return parseJspContent(content, filePath);
            
        } catch (Exception e) {
            logger.error("Failed to parse JSP file: " + filePath, e);
            return Collections.emptyList();
        }
    }
    
    private List<CodeChunk> parseJspContent(String content, Path filePath) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\\n");
        
        // Parse HTML structure using JSoup
        Document doc;
        try {
            doc = Jsoup.parse(content);
        } catch (Exception e) {
            logger.warn("Failed to parse JSP as HTML, falling back to regex parsing: {}", filePath);
            return parseWithRegex(content, filePath, lines);
        }
        
        // Extract JSP directives
        chunks.addAll(extractDirectives(content, filePath, lines));
        
        // Extract scriptlets and expressions
        chunks.addAll(extractScriptlets(content, filePath, lines));
        
        // Extract Struts tags and forms
        chunks.addAll(extractStrutsTags(doc, filePath, lines));
        
        // Extract includes and forwards
        chunks.addAll(extractIncludes(doc, content, filePath, lines));
        
        // Create main JSP fragment chunks
        chunks.addAll(createFragmentChunks(doc, filePath, lines));
        
        logger.debug("Extracted {} chunks from JSP: {}", chunks.size(), filePath);
        return chunks;
    }
    
    private List<CodeChunk> extractDirectives(String content, Path filePath, String[] lines) {
        List<CodeChunk> chunks = new ArrayList<>();
        Matcher matcher = DIRECTIVE_PATTERN.matcher(content);
        
        while (matcher.find()) {
            String directive = matcher.group();
            int startPos = matcher.start();
            int startLine = getLineNumber(content, startPos);
            int endLine = getLineNumber(content, matcher.end());
            
            Map<String, Object> metadata = parseDirectiveMetadata(directive);
            Map<String, String> mvcMapping = extractMvcMappingFromDirective(directive);
            
            CodeChunk chunk = CodeChunk.builder()
                    .id(generateId(filePath.toString(), "DIRECTIVE", startLine))
                    .content(directive)
                    .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                    .filePath(filePath.toString())
                    .fullyQualifiedName(filePath.toString() + "#directive#" + startLine)
                    .startLine(startLine)
                    .endLine(endLine)
                    .mvcMapping(mvcMapping)
                    .metadata(metadata)
                    .build();
            
            chunks.add(chunk);
        }
        
        return chunks;
    }
    
    private List<CodeChunk> extractScriptlets(String content, Path filePath, String[] lines) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        // Extract scriptlets (<% ... %>)
        Matcher scriptletMatcher = SCRIPTLET_PATTERN.matcher(content);
        while (scriptletMatcher.find()) {
            chunks.add(createScriptletChunk(scriptletMatcher, content, filePath, "SCRIPTLET"));
        }
        
        // Extract expressions (<%= ... %>)
        Matcher expressionMatcher = EXPRESSION_PATTERN.matcher(content);
        while (expressionMatcher.find()) {
            chunks.add(createScriptletChunk(expressionMatcher, content, filePath, "EXPRESSION"));
        }
        
        return chunks;
    }
    
    private CodeChunk createScriptletChunk(Matcher matcher, String content, Path filePath, String subtype) {
        String scriptlet = matcher.group();
        int startPos = matcher.start();
        int startLine = getLineNumber(content, startPos);
        int endLine = getLineNumber(content, matcher.end());
        
        // Extract Java code from scriptlet
        String javaCode = scriptlet.replaceAll("<%[=]?", "").replaceAll("%>", "").trim();
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("subtype", subtype);
        metadata.put("javaCode", javaCode);
        metadata.put("hasJavaCode", !javaCode.isEmpty());
        
        // Try to extract variable references and method calls
        List<String> apiCalls = extractJavaApiCalls(javaCode);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), subtype, startLine))
                .content(scriptlet)
                .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#" + subtype.toLowerCase() + "#" + startLine)
                .startLine(startLine)
                .endLine(endLine)
                .apiCallSequence(apiCalls)
                .metadata(metadata)
                .build();
    }
    
    private List<CodeChunk> extractStrutsTags(Document doc, Path filePath, String[] lines) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        // Extract Struts form tags
        Elements forms = doc.select("html\\:form, form");
        for (Element form : forms) {
            chunks.add(createStrutsFormChunk(form, filePath, lines));
        }
        
        // Extract Struts logic tags
        Elements logicTags = doc.select("[^*|=logic:]");
        for (Element logicTag : logicTags) {
            if (logicTag.tagName().startsWith("logic:")) {
                chunks.add(createStrutsLogicChunk(logicTag, filePath, lines));
            }
        }
        
        // Extract bean tags
        Elements beanTags = doc.select("[^*|=bean:]");
        for (Element beanTag : beanTags) {
            if (beanTag.tagName().startsWith("bean:")) {
                chunks.add(createStrutsBeanChunk(beanTag, filePath, lines));
            }
        }
        
        return chunks;
    }
    
    private CodeChunk createStrutsFormChunk(Element form, Path filePath, String[] lines) {
        String action = form.attr("action");
        String method = form.attr("method");
        
        Map<String, String> mvcMapping = new HashMap<>();
        mvcMapping.put("actionPath", action);
        mvcMapping.put("httpMethod", method);
        mvcMapping.put("formName", form.attr("name"));
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tagType", "form");
        metadata.put("fieldCount", form.select("input, select, textarea").size());
        
        // Estimate line numbers (JSoup doesn't preserve this)
        int estimatedLine = estimateLineNumber(form.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "FORM", estimatedLine))
                .content(form.outerHtml())
                .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#form#" + action)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(form.outerHtml()))
                .mvcMapping(mvcMapping)
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createStrutsLogicChunk(Element logicTag, Path filePath, String[] lines) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tagType", "logic");
        metadata.put("logicType", logicTag.tagName());
        
        // Extract logic conditions
        String name = logicTag.attr("name");
        String property = logicTag.attr("property");
        String value = logicTag.attr("value");
        
        if (!name.isEmpty()) metadata.put("beanName", name);
        if (!property.isEmpty()) metadata.put("beanProperty", property);
        if (!value.isEmpty()) metadata.put("compareValue", value);
        
        int estimatedLine = estimateLineNumber(logicTag.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "LOGIC", estimatedLine))
                .content(logicTag.outerHtml())
                .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#logic#" + estimatedLine)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(logicTag.outerHtml()))
                .metadata(metadata)
                .build();
    }
    
    private CodeChunk createStrutsBeanChunk(Element beanTag, Path filePath, String[] lines) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tagType", "bean");
        metadata.put("beanType", beanTag.tagName());
        
        String name = beanTag.attr("name");
        String property = beanTag.attr("property");
        String id = beanTag.attr("id");
        
        if (!name.isEmpty()) metadata.put("beanName", name);
        if (!property.isEmpty()) metadata.put("beanProperty", property);
        if (!id.isEmpty()) metadata.put("beanId", id);
        
        int estimatedLine = estimateLineNumber(beanTag.outerHtml(), filePath);
        
        return CodeChunk.builder()
                .id(generateId(filePath.toString(), "BEAN", estimatedLine))
                .content(beanTag.outerHtml())
                .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                .filePath(filePath.toString())
                .fullyQualifiedName(filePath.toString() + "#bean#" + estimatedLine)
                .startLine(estimatedLine)
                .endLine(estimatedLine + countLines(beanTag.outerHtml()))
                .metadata(metadata)
                .build();
    }
    
    private List<CodeChunk> extractIncludes(Document doc, String content, Path filePath, String[] lines) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        // Extract JSP includes
        Elements includes = doc.select("[^*|=jsp:include], [^*|=include]");
        for (Element include : includes) {
            String page = include.attr("page");
            if (!page.isEmpty()) {
                Map<String, String> mvcMapping = new HashMap<>();
                mvcMapping.put("includePath", page);
                
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("includeType", "jsp:include");
                metadata.put("targetPage", page);
                
                int estimatedLine = estimateLineNumber(include.outerHtml(), filePath);
                
                CodeChunk chunk = CodeChunk.builder()
                        .id(generateId(filePath.toString(), "INCLUDE", estimatedLine))
                        .content(include.outerHtml())
                        .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                        .filePath(filePath.toString())
                        .fullyQualifiedName(filePath.toString() + "#include#" + page)
                        .startLine(estimatedLine)
                        .endLine(estimatedLine)
                        .mvcMapping(mvcMapping)
                        .metadata(metadata)
                        .build();
                
                chunks.add(chunk);
            }
        }
        
        return chunks;
    }
    
    private List<CodeChunk> createFragmentChunks(Document doc, Path filePath, String[] lines) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        // Create chunks for major HTML sections
        Elements sections = doc.select("div[id], div[class*=section], div[class*=container], table, form");
        
        for (Element section : sections) {
            if (section.text().trim().length() > 50) { // Only meaningful sections
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("htmlTag", section.tagName());
                metadata.put("cssClass", section.attr("class"));
                metadata.put("htmlId", section.attr("id"));
                metadata.put("textLength", section.text().length());
                
                int estimatedLine = estimateLineNumber(section.outerHtml(), filePath);
                
                CodeChunk chunk = CodeChunk.builder()
                        .id(generateId(filePath.toString(), "HTML_SECTION", estimatedLine))
                        .content(section.outerHtml())
                        .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                        .filePath(filePath.toString())
                        .fullyQualifiedName(filePath.toString() + "#section#" + estimatedLine)
                        .startLine(estimatedLine)
                        .endLine(estimatedLine + countLines(section.outerHtml()))
                        .metadata(metadata)
                        .build();
                
                chunks.add(chunk);
            }
        }
        
        return chunks;
    }
    
    private List<CodeChunk> parseWithRegex(String content, Path filePath, String[] lines) {
        // Fallback regex-based parsing when JSoup fails
        List<CodeChunk> chunks = new ArrayList<>();
        
        Matcher strutsMatcher = STRUTS_TAG_PATTERN.matcher(content);
        while (strutsMatcher.find()) {
            String tag = strutsMatcher.group();
            int startLine = getLineNumber(content, strutsMatcher.start());
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tagType", "struts");
            metadata.put("rawTag", tag);
            
            CodeChunk chunk = CodeChunk.builder()
                    .id(generateId(filePath.toString(), "STRUTS_TAG", startLine))
                    .content(tag)
                    .type(CodeChunk.ChunkType.JSP_FRAGMENT)
                    .filePath(filePath.toString())
                    .fullyQualifiedName(filePath.toString() + "#struts_tag#" + startLine)
                    .startLine(startLine)
                    .endLine(startLine)
                    .metadata(metadata)
                    .build();
            
            chunks.add(chunk);
        }
        
        return chunks;
    }
    
    // Helper methods
    private Map<String, Object> parseDirectiveMetadata(String directive) {
        Map<String, Object> metadata = new HashMap<>();
        
        if (directive.contains("page")) {
            metadata.put("directiveType", "page");
            if (directive.contains("import")) {
                String importStr = extractAttributeValue(directive, "import");
                metadata.put("imports", Arrays.asList(importStr.split(",")));
            }
        } else if (directive.contains("taglib")) {
            metadata.put("directiveType", "taglib");
            metadata.put("prefix", extractAttributeValue(directive, "prefix"));
            metadata.put("uri", extractAttributeValue(directive, "uri"));
        } else if (directive.contains("include")) {
            metadata.put("directiveType", "include");
            metadata.put("file", extractAttributeValue(directive, "file"));
        }
        
        return metadata;
    }
    
    private Map<String, String> extractMvcMappingFromDirective(String directive) {
        Map<String, String> mapping = new HashMap<>();
        
        if (directive.contains("include")) {
            String file = extractAttributeValue(directive, "file");
            mapping.put("includePath", file);
        }
        
        return mapping;
    }
    
    private List<String> extractJavaApiCalls(String javaCode) {
        List<String> apiCalls = new ArrayList<>();
        
        // Simple pattern matching for method calls
        Pattern methodCallPattern = Pattern.compile("(\\w+\\.)+\\w+\\s*\\(");
        Matcher matcher = methodCallPattern.matcher(javaCode);
        
        while (matcher.find()) {
            String call = matcher.group().replaceAll("\\s*\\($", "");
            apiCalls.add(call);
        }
        
        return apiCalls;
    }
    
    private String extractAttributeValue(String tag, String attribute) {
        Pattern pattern = Pattern.compile(attribute + "\\s*=\\s*[\"']([^\"']*)[\"']");
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(1) : "";
    }
    
    private int getLineNumber(String content, int position) {
        return (int) content.substring(0, position).chars().filter(c -> c == '\n').count() + 1;
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
}