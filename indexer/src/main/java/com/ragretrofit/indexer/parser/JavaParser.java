package com.ragretrofit.indexer.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.ragretrofit.indexer.model.CodeChunk;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parses Java source files into structured CodeChunks with rich metadata.
 * Implements method-level chunking with sliding windows for large methods.
 */
public class JavaParser implements CodeParser {
    
    private static final Logger logger = LoggerFactory.getLogger(JavaParser.class);
    
    // Configuration for sliding windows
    private static final int MAX_METHOD_LINES = 100;
    private static final double OVERLAP_RATIO = 0.25;
    
    @Override
    public boolean canParse(Path filePath) {
        return filePath.toString().endsWith(".java");
    }
    
    @Override
    public List<CodeChunk> parse(Path filePath) {
        logger.debug("Parsing Java file: {}", filePath);
        
        try {
            String content = IOUtils.toString(new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8);
            CompilationUnit cu = StaticJavaParser.parse(content);
            
            return parseCompilationUnit(cu, filePath, content);
            
        } catch (Exception e) {
            logger.error("Failed to parse Java file: " + filePath, e);
            return Collections.emptyList();
        }
    }
    
    private List<CodeChunk> parseCompilationUnit(CompilationUnit cu, Path filePath, String originalContent) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = originalContent.split("\\n");
        
        // Extract package and imports
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
                
        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .toList();
        
        // Parse classes and interfaces
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = classDecl.getNameAsString();
            String fqn = packageName.isEmpty() ? className : packageName + "." + className;
            String classContext = extractClassContext(classDecl, lines);
            
            // Create class chunk
            chunks.add(createClassChunk(classDecl, filePath, fqn, packageName, imports, classContext, lines));
            
            // Parse methods
            classDecl.findAll(MethodDeclaration.class).forEach(method -> {
                chunks.addAll(parseMethod(method, filePath, fqn, packageName, imports, classContext, lines));
            });
            
            // Parse fields
            classDecl.findAll(FieldDeclaration.class).forEach(field -> {
                chunks.add(parseField(field, filePath, fqn, packageName, imports, classContext, lines));
            });
        });
        
        logger.debug("Extracted {} chunks from {}", chunks.size(), filePath);
        return chunks;
    }
    
    private CodeChunk createClassChunk(ClassOrInterfaceDeclaration classDecl, Path filePath,
                                     String fqn, String packageName, List<String> imports,
                                     String classContext, String[] lines) {
        
        int startLine = classDecl.getBegin().map(pos -> pos.line).orElse(1);
        int endLine = classDecl.getEnd().map(pos -> pos.line).orElse(lines.length);
        
        String content = extractLines(lines, startLine, endLine);
        List<String> annotations = classDecl.getAnnotations().stream()
                .map(ann -> ann.toString())
                .toList();
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("isInterface", classDecl.isInterface());
        metadata.put("isAbstract", classDecl.isAbstract());
        metadata.put("methodCount", classDecl.getMethods().size());
        metadata.put("fieldCount", classDecl.getFields().size());
        
        return CodeChunk.builder()
                .id(generateId(fqn, "CLASS"))
                .content(content)
                .type(CodeChunk.ChunkType.CLASS)
                .filePath(filePath.toString())
                .fullyQualifiedName(fqn)
                .startLine(startLine)
                .endLine(endLine)
                .imports(imports)
                .annotations(annotations)
                .packageContext(packageName)
                .metadata(metadata)
                .build();
    }
    
    private List<CodeChunk> parseMethod(MethodDeclaration method, Path filePath, String classFqn,
                                      String packageName, List<String> imports, String classContext,
                                      String[] lines) {
        
        int startLine = method.getBegin().map(pos -> pos.line).orElse(1);
        int endLine = method.getEnd().map(pos -> pos.line).orElse(lines.length);
        int methodLines = endLine - startLine + 1;
        
        String methodName = method.getNameAsString();
        String methodFqn = classFqn + "." + methodName;
        
        List<String> annotations = method.getAnnotations().stream()
                .map(ann -> ann.toString())
                .toList();
        
        List<String> apiCalls = extractApiCalls(method);
        Map<String, String> mvcMapping = extractMvcMapping(method, annotations);
        
        Map<String, Object> metadata = createMethodMetadata(method);
        
        // If method is small enough, create single chunk
        if (methodLines <= MAX_METHOD_LINES) {
            String content = extractLines(lines, startLine, endLine);
            
            CodeChunk chunk = CodeChunk.builder()
                    .id(generateId(methodFqn, "METHOD"))
                    .content(content)
                    .type(CodeChunk.ChunkType.METHOD)
                    .filePath(filePath.toString())
                    .fullyQualifiedName(methodFqn)
                    .startLine(startLine)
                    .endLine(endLine)
                    .imports(imports)
                    .annotations(annotations)
                    .classContext(classContext)
                    .packageContext(packageName)
                    .apiCallSequence(apiCalls)
                    .mvcMapping(mvcMapping)
                    .metadata(metadata)
                    .build();
            
            return List.of(chunk);
        }
        
        // Create sliding window chunks for large methods
        return createSlidingWindowChunks(method, lines, startLine, endLine, filePath, 
                methodFqn, packageName, imports, annotations, classContext, apiCalls, mvcMapping, metadata);
    }
    
    private List<CodeChunk> createSlidingWindowChunks(MethodDeclaration method, String[] lines,
                                                     int startLine, int endLine, Path filePath,
                                                     String methodFqn, String packageName,
                                                     List<String> imports, List<String> annotations,
                                                     String classContext, List<String> apiCalls,
                                                     Map<String, String> mvcMapping,
                                                     Map<String, Object> metadata) {
        
        List<CodeChunk> chunks = new ArrayList<>();
        int windowSize = MAX_METHOD_LINES;
        int overlap = (int) (windowSize * OVERLAP_RATIO);
        int step = windowSize - overlap;
        
        AtomicInteger chunkIndex = new AtomicInteger(0);
        
        for (int i = startLine; i <= endLine; i += step) {
            int chunkStart = i;
            int chunkEnd = Math.min(i + windowSize - 1, endLine);
            
            String content = extractLines(lines, chunkStart, chunkEnd);
            
            Map<String, Object> chunkMetadata = new HashMap<>(metadata);
            chunkMetadata.put("windowIndex", chunkIndex.getAndIncrement());
            chunkMetadata.put("isPartial", true);
            chunkMetadata.put("totalWindows", (endLine - startLine + step - 1) / step);
            
            CodeChunk chunk = CodeChunk.builder()
                    .id(generateId(methodFqn, "METHOD_" + chunkIndex.get()))
                    .content(content)
                    .type(CodeChunk.ChunkType.METHOD)
                    .filePath(filePath.toString())
                    .fullyQualifiedName(methodFqn)
                    .startLine(chunkStart)
                    .endLine(chunkEnd)
                    .imports(imports)
                    .annotations(annotations)
                    .classContext(classContext)
                    .packageContext(packageName)
                    .apiCallSequence(apiCalls)
                    .mvcMapping(mvcMapping)
                    .metadata(chunkMetadata)
                    .build();
            
            chunks.add(chunk);
            
            // Break if we've covered the entire method
            if (chunkEnd >= endLine) break;
        }
        
        return chunks;
    }
    
    private CodeChunk parseField(FieldDeclaration field, Path filePath, String classFqn,
                               String packageName, List<String> imports, String classContext,
                               String[] lines) {
        
        int startLine = field.getBegin().map(pos -> pos.line).orElse(1);
        int endLine = field.getEnd().map(pos -> pos.line).orElse(startLine);
        
        String fieldName = field.getVariables().get(0).getNameAsString();
        String fieldFqn = classFqn + "." + fieldName;
        String content = extractLines(lines, startLine, endLine);
        
        List<String> annotations = field.getAnnotations().stream()
                .map(ann -> ann.toString())
                .toList();
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", field.getElementType().asString());
        metadata.put("isStatic", field.isStatic());
        metadata.put("isFinal", field.isFinal());
        metadata.put("isPrivate", field.isPrivate());
        metadata.put("isPublic", field.isPublic());
        
        return CodeChunk.builder()
                .id(generateId(fieldFqn, "FIELD"))
                .content(content)
                .type(CodeChunk.ChunkType.FIELD)
                .filePath(filePath.toString())
                .fullyQualifiedName(fieldFqn)
                .startLine(startLine)
                .endLine(endLine)
                .imports(imports)
                .annotations(annotations)
                .classContext(classContext)
                .packageContext(packageName)
                .metadata(metadata)
                .build();
    }
    
    private List<String> extractApiCalls(MethodDeclaration method) {
        List<String> apiCalls = new ArrayList<>();
        
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                // Extract method call sequence for API analysis
                String call = n.getScope()
                        .map(scope -> scope.toString() + "." + n.getNameAsString())
                        .orElse(n.getNameAsString());
                apiCalls.add(call);
                super.visit(n, arg);
            }
        }, null);
        
        return apiCalls;
    }
    
    private Map<String, String> extractMvcMapping(MethodDeclaration method, List<String> annotations) {
        Map<String, String> mvcMapping = new HashMap<>();
        
        // Extract Struts action mappings from annotations or method name patterns
        for (String annotation : annotations) {
            if (annotation.contains("@Action") || annotation.contains("@RequestMapping")) {
                // Parse annotation for path/value
                mvcMapping.put("actionPath", extractAnnotationValue(annotation));
            }
        }
        
        // Infer from method name patterns
        String methodName = method.getNameAsString();
        if (methodName.startsWith("execute") || methodName.endsWith("Action")) {
            mvcMapping.put("actionMethod", methodName);
        }
        
        return mvcMapping;
    }
    
    private String extractAnnotationValue(String annotation) {
        // Simple annotation value extraction
        int valueStart = annotation.indexOf("\"");
        int valueEnd = annotation.lastIndexOf("\"");
        if (valueStart != -1 && valueEnd > valueStart) {
            return annotation.substring(valueStart + 1, valueEnd);
        }
        return annotation;
    }
    
    private Map<String, Object> createMethodMetadata(MethodDeclaration method) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("parameterCount", method.getParameters().size());
        metadata.put("isStatic", method.isStatic());
        metadata.put("isPublic", method.isPublic());
        metadata.put("isPrivate", method.isPrivate());
        metadata.put("returnType", method.getTypeAsString());
        metadata.put("complexity", estimateComplexity(method));
        return metadata;
    }
    
    private int estimateComplexity(MethodDeclaration method) {
        // Simple cyclomatic complexity estimate
        AtomicInteger complexity = new AtomicInteger(1);
        
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(com.github.javaparser.ast.stmt.IfStmt n, Void arg) {
                complexity.incrementAndGet();
                super.visit(n, arg);
            }
            
            @Override
            public void visit(com.github.javaparser.ast.stmt.WhileStmt n, Void arg) {
                complexity.incrementAndGet();
                super.visit(n, arg);
            }
            
            @Override
            public void visit(com.github.javaparser.ast.stmt.ForStmt n, Void arg) {
                complexity.incrementAndGet();
                super.visit(n, arg);
            }
            
            @Override
            public void visit(com.github.javaparser.ast.stmt.SwitchStmt n, Void arg) {
                complexity.addAndGet(n.getEntries().size());
                super.visit(n, arg);
            }
        }, null);
        
        return complexity.get();
    }
    
    private String extractClassContext(ClassOrInterfaceDeclaration classDecl, String[] lines) {
        StringBuilder context = new StringBuilder();
        context.append(classDecl.isInterface() ? "interface " : "class ");
        context.append(classDecl.getNameAsString());
        
        if (!classDecl.getExtendedTypes().isEmpty()) {
            context.append(" extends ");
            context.append(classDecl.getExtendedTypes().get(0).getNameAsString());
        }
        
        if (!classDecl.getImplementedTypes().isEmpty()) {
            context.append(" implements ");
            context.append(classDecl.getImplementedTypes().stream()
                    .map(type -> type.getNameAsString())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
        }
        
        return context.toString();
    }
    
    private String extractLines(String[] lines, int startLine, int endLine) {
        StringBuilder content = new StringBuilder();
        int start = Math.max(0, startLine - 1); // Convert to 0-based
        int end = Math.min(lines.length, endLine);
        
        for (int i = start; i < end; i++) {
            if (i > start) content.append("\n");
            content.append(lines[i]);
        }
        
        return content.toString();
    }
    
    private String generateId(String fqn, String type) {
        return fqn + "#" + type + "#" + System.currentTimeMillis();
    }
}