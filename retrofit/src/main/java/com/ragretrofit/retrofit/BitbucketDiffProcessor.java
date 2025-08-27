package com.ragretrofit.retrofit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes Bitbucket diffs via REST API and extracts structured change information.
 * Handles authentication, diff parsing, and change categorization.
 */
public class BitbucketDiffProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(BitbucketDiffProcessor.class);
    
    // Bitbucket API patterns
    private static final Pattern BITBUCKET_URL_PATTERN = Pattern.compile(
            "https?://([^/]+)/(.+?)/(.+?)/commit/([a-f0-9]+)(?:/diff/(.+?))?");
    
    // Unified diff patterns
    private static final Pattern DIFF_HEADER_PATTERN = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern HUNK_HEADER_PATTERN = Pattern.compile("^@@\\s+-([0-9]+)(?:,([0-9]+))?\\s+\\+([0-9]+)(?:,([0-9]+))?\\s+@@(.*)$");
    private static final Pattern BINARY_PATTERN = Pattern.compile("^Binary files .+ differ$");
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String bitbucketToken; // Basic auth or app password
    private final String bitbucketUser;
    
    public BitbucketDiffProcessor(String bitbucketUser, String bitbucketToken) {
        this.bitbucketUser = Objects.requireNonNull(bitbucketUser, "Bitbucket user cannot be null");
        this.bitbucketToken = Objects.requireNonNull(bitbucketToken, "Bitbucket token cannot be null");
        this.objectMapper = new ObjectMapper();
        
        // Configure HTTP client with authentication
        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    
                    String credentials = Credentials.basic(bitbucketUser, bitbucketToken);
                    Request authenticated = original.newBuilder()
                            .header("Authorization", credentials)
                            .header("Accept", "application/json")
                            .build();
                    
                    return chain.proceed(authenticated);
                })
                .build();
        
        logger.info("Initialized Bitbucket diff processor for user: {}", bitbucketUser);
    }
    
    /**
     * Process a Bitbucket commit URL and extract diff information
     */
    public DiffResult processDiff(String bitbucketUrl) throws IOException {
        logger.info("Processing Bitbucket diff: {}", bitbucketUrl);
        
        BitbucketUrlInfo urlInfo = parseBitbucketUrl(bitbucketUrl);
        
        // Get commit metadata
        CommitInfo commitInfo = fetchCommitInfo(urlInfo);
        
        // Get raw diff content
        String rawDiff = fetchRawDiff(urlInfo);
        
        // Parse unified diff
        List<FileChange> fileChanges = parseUnifiedDiff(rawDiff);
        
        DiffResult result = new DiffResult(
                urlInfo,
                commitInfo,
                fileChanges,
                rawDiff
        );
        
        logger.info("Processed diff with {} file changes", fileChanges.size());
        return result;
    }
    
    /**
     * Parse Bitbucket URL to extract repository and commit info
     */
    private BitbucketUrlInfo parseBitbucketUrl(String url) {
        Matcher matcher = BITBUCKET_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Bitbucket URL format: " + url);
        }
        
        String host = matcher.group(1);
        String workspace = matcher.group(2);
        String repository = matcher.group(3);
        String commitHash = matcher.group(4);
        String specificFile = matcher.group(5); // May be null
        
        return new BitbucketUrlInfo(host, workspace, repository, commitHash, specificFile);
    }
    
    /**
     * Fetch commit metadata from Bitbucket API
     */
    private CommitInfo fetchCommitInfo(BitbucketUrlInfo urlInfo) throws IOException {
        String apiUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commit/%s",
                urlInfo.getWorkspace(), urlInfo.getRepository(), urlInfo.getCommitHash());
        
        Request request = new Request.Builder()
                .url(apiUrl)
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch commit info: " + response.code() + " " + response.message());
            }
            
            String responseBody = response.body().string();
            JsonNode commitNode = objectMapper.readTree(responseBody);
            
            String message = commitNode.path("message").asText();
            String author = commitNode.path("author").path("user").path("display_name").asText();
            String date = commitNode.path("date").asText();
            
            List<String> parents = new ArrayList<>();
            JsonNode parentsNode = commitNode.path("parents");
            if (parentsNode.isArray()) {
                parentsNode.forEach(parent -> parents.add(parent.path("hash").asText()));
            }
            
            return new CommitInfo(urlInfo.getCommitHash(), message, author, date, parents);
        }
    }
    
    /**
     * Fetch raw diff content from Bitbucket API
     */
    private String fetchRawDiff(BitbucketUrlInfo urlInfo) throws IOException {
        String apiUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/diff/%s",
                urlInfo.getWorkspace(), urlInfo.getRepository(), urlInfo.getCommitHash());
        
        // If specific file requested, add path parameter
        if (urlInfo.getSpecificFile() != null) {
            apiUrl += "?path=" + urlInfo.getSpecificFile();
        }
        
        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Accept", "text/plain") // Get raw diff, not JSON
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch diff: " + response.code() + " " + response.message());
            }
            
            return response.body().string();
        }
    }
    
    /**
     * Parse unified diff format into structured changes
     */
    private List<FileChange> parseUnifiedDiff(String rawDiff) {
        List<FileChange> fileChanges = new ArrayList<>();
        
        String[] lines = rawDiff.split("\\n");
        FileChange currentFile = null;
        List<Hunk> currentHunks = new ArrayList<>();
        Hunk currentHunk = null;
        List<String> currentHunkLines = new ArrayList<>();
        
        for (String line : lines) {
            // Check for new file diff
            Matcher diffHeaderMatcher = DIFF_HEADER_PATTERN.matcher(line);
            if (diffHeaderMatcher.matches()) {
                // Save previous file if exists
                if (currentFile != null) {
                    if (currentHunk != null) {
                        currentHunk.setLines(new ArrayList<>(currentHunkLines));
                        currentHunks.add(currentHunk);
                    }
                    currentFile.setHunks(new ArrayList<>(currentHunks));
                    fileChanges.add(currentFile);
                }
                
                // Start new file
                String oldPath = diffHeaderMatcher.group(1);
                String newPath = diffHeaderMatcher.group(2);
                currentFile = new FileChange(oldPath, newPath);
                currentHunks.clear();
                currentHunk = null;
                currentHunkLines.clear();
                continue;
            }
            
            // Check for binary file
            if (BINARY_PATTERN.matcher(line).matches()) {
                if (currentFile != null) {
                    currentFile.setBinary(true);
                }
                continue;
            }
            
            // Check for hunk header
            Matcher hunkHeaderMatcher = HUNK_HEADER_PATTERN.matcher(line);
            if (hunkHeaderMatcher.matches()) {
                // Save previous hunk if exists
                if (currentHunk != null) {
                    currentHunk.setLines(new ArrayList<>(currentHunkLines));
                    currentHunks.add(currentHunk);
                }
                
                // Start new hunk
                int oldStart = Integer.parseInt(hunkHeaderMatcher.group(1));
                int oldCount = hunkHeaderMatcher.group(2) != null ? 
                        Integer.parseInt(hunkHeaderMatcher.group(2)) : 1;
                int newStart = Integer.parseInt(hunkHeaderMatcher.group(3));
                int newCount = hunkHeaderMatcher.group(4) != null ? 
                        Integer.parseInt(hunkHeaderMatcher.group(4)) : 1;
                String context = StringUtils.trimToEmpty(hunkHeaderMatcher.group(5));
                
                currentHunk = new Hunk(oldStart, oldCount, newStart, newCount, context);
                currentHunkLines.clear();
                continue;
            }
            
            // Regular diff line (if we're in a hunk)
            if (currentHunk != null) {
                currentHunkLines.add(line);
            }
        }
        
        // Save final file and hunk
        if (currentFile != null) {
            if (currentHunk != null) {
                currentHunk.setLines(new ArrayList<>(currentHunkLines));
                currentHunks.add(currentHunk);
            }
            currentFile.setHunks(new ArrayList<>(currentHunks));
            fileChanges.add(currentFile);
        }
        
        // Analyze file changes
        fileChanges.forEach(this::analyzeFileChange);
        
        return fileChanges;
    }
    
    /**
     * Analyze file change to determine type and extract metadata
     */
    private void analyzeFileChange(FileChange fileChange) {
        String oldPath = fileChange.getOldPath();
        String newPath = fileChange.getNewPath();
        
        // Determine change type
        if (oldPath.equals("/dev/null")) {
            fileChange.setChangeType(FileChange.ChangeType.ADDED);
        } else if (newPath.equals("/dev/null")) {
            fileChange.setChangeType(FileChange.ChangeType.DELETED);
        } else if (!oldPath.equals(newPath)) {
            fileChange.setChangeType(FileChange.ChangeType.RENAMED);
        } else {
            fileChange.setChangeType(FileChange.ChangeType.MODIFIED);
        }
        
        // Determine file type
        String filePath = !newPath.equals("/dev/null") ? newPath : oldPath;
        fileChange.setFileType(determineFileType(filePath));
        
        // Calculate statistics
        int addedLines = 0;
        int deletedLines = 0;
        
        for (Hunk hunk : fileChange.getHunks()) {
            for (String line : hunk.getLines()) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    addedLines++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    deletedLines++;
                }
            }
        }
        
        fileChange.setAddedLines(addedLines);
        fileChange.setDeletedLines(deletedLines);
        
        logger.debug("Analyzed file change: {} ({}, +{} -{} lines)",
                filePath, fileChange.getChangeType(), addedLines, deletedLines);
    }
    
    private FileChange.FileType determineFileType(String filePath) {
        String extension = StringUtils.substringAfterLast(filePath, ".").toLowerCase();
        String fileName = StringUtils.substringAfterLast(filePath, "/");
        
        switch (extension) {
            case "java":
                return FileChange.FileType.JAVA;
            case "jsp":
            case "jspx":
                return FileChange.FileType.JSP;
            case "xml":
                if (fileName.equals("web.xml") || fileName.equals("struts-config.xml") ||
                    fileName.equals("tiles-defs.xml") || fileName.contains("struts")) {
                    return FileChange.FileType.CONFIG;
                }
                return FileChange.FileType.XML;
            case "properties":
                return FileChange.FileType.PROPERTIES;
            case "sql":
                return FileChange.FileType.SQL;
            case "js":
                return FileChange.FileType.JAVASCRIPT;
            case "css":
                return FileChange.FileType.CSS;
            default:
                return FileChange.FileType.OTHER;
        }
    }
    
    // Data classes
    public static class BitbucketUrlInfo {
        private final String host;
        private final String workspace;
        private final String repository;
        private final String commitHash;
        private final String specificFile;
        
        public BitbucketUrlInfo(String host, String workspace, String repository, 
                              String commitHash, String specificFile) {
            this.host = host;
            this.workspace = workspace;
            this.repository = repository;
            this.commitHash = commitHash;
            this.specificFile = specificFile;
        }
        
        // Getters
        public String getHost() { return host; }
        public String getWorkspace() { return workspace; }
        public String getRepository() { return repository; }
        public String getCommitHash() { return commitHash; }
        public String getSpecificFile() { return specificFile; }
        
        @Override
        public String toString() {
            return String.format("BitbucketUrlInfo{workspace='%s', repo='%s', commit='%s'}",
                    workspace, repository, commitHash);
        }
    }
    
    public static class CommitInfo {
        private final String hash;
        private final String message;
        private final String author;
        private final String date;
        private final List<String> parents;
        
        public CommitInfo(String hash, String message, String author, String date, List<String> parents) {
            this.hash = hash;
            this.message = message;
            this.author = author;
            this.date = date;
            this.parents = Collections.unmodifiableList(parents);
        }
        
        // Getters
        public String getHash() { return hash; }
        public String getMessage() { return message; }
        public String getAuthor() { return author; }
        public String getDate() { return date; }
        public List<String> getParents() { return parents; }
        
        @Override
        public String toString() {
            return String.format("CommitInfo{hash='%s', author='%s', message='%s'}",
                    hash.substring(0, 8), author, 
                    message.length() > 50 ? message.substring(0, 50) + "..." : message);
        }
    }
    
    public static class DiffResult {
        private final BitbucketUrlInfo urlInfo;
        private final CommitInfo commitInfo;
        private final List<FileChange> fileChanges;
        private final String rawDiff;
        
        public DiffResult(BitbucketUrlInfo urlInfo, CommitInfo commitInfo, 
                         List<FileChange> fileChanges, String rawDiff) {
            this.urlInfo = urlInfo;
            this.commitInfo = commitInfo;
            this.fileChanges = Collections.unmodifiableList(fileChanges);
            this.rawDiff = rawDiff;
        }
        
        // Getters
        public BitbucketUrlInfo getUrlInfo() { return urlInfo; }
        public CommitInfo getCommitInfo() { return commitInfo; }
        public List<FileChange> getFileChanges() { return fileChanges; }
        public String getRawDiff() { return rawDiff; }
        
        public List<FileChange> getJavaChanges() {
            return fileChanges.stream()
                    .filter(fc -> fc.getFileType() == FileChange.FileType.JAVA)
                    .toList();
        }
        
        public List<FileChange> getJspChanges() {
            return fileChanges.stream()
                    .filter(fc -> fc.getFileType() == FileChange.FileType.JSP)
                    .toList();
        }
        
        public List<FileChange> getConfigChanges() {
            return fileChanges.stream()
                    .filter(fc -> fc.getFileType() == FileChange.FileType.CONFIG)
                    .toList();
        }
        
        @Override
        public String toString() {
            return String.format("DiffResult{commit='%s', files=%d, java=%d, jsp=%d, config=%d}",
                    commitInfo.getHash().substring(0, 8), fileChanges.size(),
                    getJavaChanges().size(), getJspChanges().size(), getConfigChanges().size());
        }
    }
}