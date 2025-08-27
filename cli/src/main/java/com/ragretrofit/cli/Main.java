package com.ragretrofit.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Main CLI entry point for the RAG Retrofit Pipeline.
 * Provides commands for indexing, retrieval testing, and retrofit operations.
 */
@Command(name = "rag-retrofit", 
         mixinStandardHelpOptions = true,
         version = "RAG Retrofit 1.0.0",
         description = "Local-first RAG pipeline for Java EE monolith retrofitting",
         subcommands = {
             IndexCommand.class,
             SearchCommand.class,
             RetrofitCommand.class,
             StatusCommand.class
         })
public class Main implements Callable<Integer> {
    
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
    
    @Override
    public Integer call() {
        System.out.println("RAG Retrofit Pipeline v1.0.0");
        System.out.println("Use --help to see available commands");
        return 0;
    }
}

/**
 * Index command for building/updating the knowledge base
 */
@Command(name = "index", 
         description = "Build or update the RAG knowledge base from source code")
class IndexCommand implements Callable<Integer> {
    
    private static final Logger logger = LoggerFactory.getLogger(IndexCommand.class);
    
    @Parameters(index = "0", description = "Path to the source code repository")
    private Path sourcePath;
    
    @Option(names = {"-o", "--output"}, 
            description = "Output directory for indices (default: ./rag-index)")
    private Path outputPath = Paths.get("./rag-index");
    
    @Option(names = {"-f", "--force"}, 
            description = "Force rebuild of existing indices")
    private boolean force = false;
    
    @Option(names = {"-t", "--threads"}, 
            description = "Number of processing threads (default: 4)")
    private int threads = 4;
    
    @Option(names = {"-v", "--verbose"}, 
            description = "Enable verbose logging")
    private boolean verbose = false;
    
    @Override
    public Integer call() {
        try {
            if (verbose) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }
            
            logger.info("Starting indexing process");
            logger.info("Source path: {}", sourcePath.toAbsolutePath());
            logger.info("Output path: {}", outputPath.toAbsolutePath());
            logger.info("Force rebuild: {}", force);
            logger.info("Threads: {}", threads);
            
            IndexingOrchestrator orchestrator = new IndexingOrchestrator(
                    sourcePath, outputPath, threads, force);
            
            IndexingResult result = orchestrator.buildIndex();
            
            System.out.println("\n" + "=".repeat(60));
            System.out.println("INDEXING COMPLETED");
            System.out.println("=".repeat(60));
            System.out.printf("Total files processed: %d%n", result.getTotalFiles());
            System.out.printf("Java files: %d%n", result.getJavaFiles());
            System.out.printf("JSP files: %d%n", result.getJspFiles());
            System.out.printf("Config files: %d%n", result.getConfigFiles());
            System.out.printf("Total chunks created: %d%n", result.getTotalChunks());
            System.out.printf("BM25 index size: %d documents%n", result.getBm25IndexSize());
            System.out.printf("Vector index size: %d embeddings%n", result.getVectorIndexSize());
            System.out.printf("Processing time: %.2f seconds%n", result.getProcessingTimeSeconds());
            System.out.println("=".repeat(60));
            
            return 0;
            
        } catch (Exception e) {
            logger.error("Indexing failed", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}

/**
 * Search command for testing retrieval functionality
 */
@Command(name = "search", 
         description = "Test search and retrieval against the knowledge base")
class SearchCommand implements Callable<Integer> {
    
    private static final Logger logger = LoggerFactory.getLogger(SearchCommand.class);
    
    @Parameters(index = "0", description = "Search query")
    private String query;
    
    @Option(names = {"-i", "--index"}, 
            description = "Path to the index directory (default: ./rag-index)")
    private Path indexPath = Paths.get("./rag-index");
    
    @Option(names = {"-n", "--num-results"}, 
            description = "Number of results to return (default: 10)")
    private int numResults = 10;
    
    @Option(names = {"-t", "--type"}, 
            description = "Filter by chunk type: METHOD, CLASS, JSP_FRAGMENT, CONFIG_SECTION")
    private String chunkType;
    
    @Option(names = {"--openai-key"}, 
            description = "OpenAI API key for LLM reranking")
    private String openaiKey;
    
    @Option(names = {"-v", "--verbose"}, 
            description = "Show detailed search process")
    private boolean verbose = false;
    
    @Override
    public Integer call() {
        try {
            if (verbose) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }
            
            logger.info("Starting search for: '{}'", query);
            logger.info("Index path: {}", indexPath.toAbsolutePath());
            logger.info("Max results: {}", numResults);
            
            SearchOrchestrator orchestrator = new SearchOrchestrator(indexPath, openaiKey);
            
            SearchResult result = orchestrator.search(query, numResults, chunkType);
            
            System.out.println("\n" + "=".repeat(80));
            System.out.println("SEARCH RESULTS");
            System.out.println("=".repeat(80));
            System.out.printf("Query: %s%n", query);
            System.out.printf("Found: %d results in %.2f seconds%n", 
                    result.getResults().size(), result.getSearchTimeSeconds());
            System.out.println("-".repeat(80));
            
            int rank = 1;
            for (SearchResultEntry entry : result.getResults()) {
                System.out.printf("%n[%d] Score: %.3f | Type: %s%n", 
                        rank++, entry.getScore(), entry.getType());
                System.out.printf("File: %s:%d-%d%n", 
                        entry.getFilePath(), entry.getStartLine(), entry.getEndLine());
                if (entry.getFqn() != null) {
                    System.out.printf("FQN: %s%n", entry.getFqn());
                }
                System.out.println("Content:");
                String content = entry.getContent();
                if (content.length() > 200) {
                    System.out.println(content.substring(0, 200) + "...");
                } else {
                    System.out.println(content);
                }
            }
            
            System.out.println("=".repeat(80));
            return 0;
            
        } catch (Exception e) {
            logger.error("Search failed", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}

/**
 * Retrofit command for applying changes from source to target repo
 */
@Command(name = "retrofit", 
         description = "Apply changes from Bitbucket diff to target codebase")
class RetrofitCommand implements Callable<Integer> {
    
    private static final Logger logger = LoggerFactory.getLogger(RetrofitCommand.class);
    
    @Parameters(index = "0", description = "Bitbucket commit/diff URL")
    private String bitbucketUrl;
    
    @Parameters(index = "1", description = "Path to target repository")
    private Path targetRepo;
    
    @Option(names = {"-i", "--index"}, 
            description = "Path to the target repo index (default: ./target-index)")
    private Path indexPath = Paths.get("./target-index");
    
    @Option(names = {"-o", "--output"}, 
            description = "Output directory for patches and reports (default: ./retrofit-output)")
    private Path outputPath = Paths.get("./retrofit-output");
    
    @Option(names = {"--bitbucket-user"}, required = true,
            description = "Bitbucket username")
    private String bitbucketUser;
    
    @Option(names = {"--bitbucket-token"}, required = true,
            description = "Bitbucket app password or token")
    private String bitbucketToken;
    
    @Option(names = {"--openai-key"}, required = true,
            description = "OpenAI API key for patch synthesis")
    private String openaiKey;
    
    @Option(names = {"--dry-run"}, 
            description = "Generate patches without applying them")
    private boolean dryRun = false;
    
    @Option(names = {"--skip-validation"}, 
            description = "Skip compilation and test validation")
    private boolean skipValidation = false;
    
    @Option(names = {"-v", "--verbose"}, 
            description = "Enable verbose logging")
    private boolean verbose = false;
    
    @Override
    public Integer call() {
        try {
            if (verbose) {
                System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
            }
            
            logger.info("Starting retrofit process");
            logger.info("Bitbucket URL: {}", bitbucketUrl);
            logger.info("Target repository: {}", targetRepo.toAbsolutePath());
            logger.info("Index path: {}", indexPath.toAbsolutePath());
            logger.info("Output path: {}", outputPath.toAbsolutePath());
            logger.info("Dry run: {}", dryRun);
            
            RetrofitOrchestrator orchestrator = new RetrofitOrchestrator(
                    indexPath, targetRepo, outputPath, 
                    bitbucketUser, bitbucketToken, openaiKey);
            
            RetrofitResult result = orchestrator.processRetrofit(
                    bitbucketUrl, dryRun, !skipValidation);
            
            System.out.println("\n" + "=".repeat(80));
            System.out.println("RETROFIT COMPLETED");
            System.out.println("=".repeat(80));
            System.out.printf("Source changes analyzed: %d%n", result.getSourceChanges());
            System.out.printf("Target mappings found: %d%n", result.getTargetMappings());
            System.out.printf("Patches generated: %d%n", result.getPatchesGenerated());
            System.out.printf("Patches applied: %d%n", result.getPatchesApplied());
            System.out.printf("Validation errors: %d%n", result.getValidationErrors());
            System.out.printf("Processing time: %.2f seconds%n", result.getProcessingTimeSeconds());
            
            System.out.println("\nOutput files:");
            System.out.printf("- Change plan: %s%n", result.getChangePlanPath());
            System.out.printf("- Applied patches: %s%n", result.getAppliedPatchesPath());
            if (result.getValidationReportPath() != null) {
                System.out.printf("- Validation report: %s%n", result.getValidationReportPath());
            }
            
            System.out.println("=".repeat(80));
            
            return result.getValidationErrors() == 0 ? 0 : 2;
            
        } catch (Exception e) {
            logger.error("Retrofit failed", e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}

/**
 * Status command for checking system status and configuration
 */
@Command(name = "status", 
         description = "Show system status and configuration")
class StatusCommand implements Callable<Integer> {
    
    @Option(names = {"-i", "--index"}, 
            description = "Path to index directory to check (default: ./rag-index)")
    private Path indexPath = Paths.get("./rag-index");
    
    @Override
    public Integer call() {
        System.out.println("RAG Retrofit Pipeline Status");
        System.out.println("=".repeat(40));
        
        // System info
        System.out.printf("Java version: %s%n", System.getProperty("java.version"));
        System.out.printf("Available processors: %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("Max memory: %d MB%n", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        
        // Index status
        System.out.println("\nIndex Status:");
        System.out.printf("Index path: %s%n", indexPath.toAbsolutePath());
        
        if (indexPath.toFile().exists()) {
            System.out.println("✓ Index directory exists");
            
            // Check for index files
            Path bm25Path = indexPath.resolve("bm25");
            Path vectorPath = indexPath.resolve("vectors.json");
            Path graphPath = indexPath.resolve("graph");
            
            System.out.printf("  BM25 index: %s%n", bm25Path.toFile().exists() ? "✓ Present" : "✗ Missing");
            System.out.printf("  Vector index: %s%n", vectorPath.toFile().exists() ? "✓ Present" : "✗ Missing");
            System.out.printf("  Graph index: %s%n", graphPath.toFile().exists() ? "✓ Present" : "✗ Missing");
        } else {
            System.out.println("✗ Index directory does not exist");
            System.out.println("  Run 'rag-retrofit index <source-path>' to create indices");
        }
        
        // Environment checks
        System.out.println("\nEnvironment:");
        System.out.printf("OPENAI_API_KEY: %s%n", 
                System.getenv("OPENAI_API_KEY") != null ? "✓ Set" : "✗ Not set");
        System.out.printf("BITBUCKET_USER: %s%n", 
                System.getenv("BITBUCKET_USER") != null ? "✓ Set" : "✗ Not set");
        System.out.printf("BITBUCKET_TOKEN: %s%n", 
                System.getenv("BITBUCKET_TOKEN") != null ? "✓ Set" : "✗ Not set");
        
        return 0;
    }
}