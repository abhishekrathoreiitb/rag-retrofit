# RAG Retrofit Pipeline

A local-first retrieval-augmented generation (RAG) pipeline designed for retrofitting changes across large Java EE monoliths. This system provides intelligent code mapping and transformation capabilities for enterprise codebases with millions of lines of code.

## 🎯 Overview

The RAG Retrofit Pipeline addresses the challenge of applying changes from one Java EE codebase to another by using advanced retrieval techniques and LLM-assisted patch synthesis. It's specifically designed for:

- **Large Java EE monoliths** (Struts + JSPs, millions of LOC)
- **Structure-aware code chunking** and indexing
- **Hybrid retrieval** (BM25 + Vector + Graph + LLM reranking)
- **AST-safe transformations** with fallback to LLM assistance
- **Local-first processing** with cloud LLM for reasoning only

## 🏗 Architecture

### Core Components

1. **Indexer Module** - Parses Java, JSP, and config files into structured chunks
2. **Stores Module** - BM25 (Lucene), Vector (MiniLM v2), and Graph indices  
3. **Retrieval Module** - LangChain4j-based hybrid retrieval pipeline
4. **Retrofit Module** - Diff processing, mapping, and AST transformations
5. **CLI Module** - Command-line interface for all operations

### Retrieval Pipeline
```
BM25 Prefilter → Graph Filter → Vector Recall → LLM Rerank → Final Results
```

### Tech Stack
- **Java 17+** with Maven multi-module project
- **LangChain4j** for chain orchestration and LLM integration
- **JavaParser** for Java AST parsing and manipulation
- **Lucene** for BM25 lexical search
- **MiniLM v2** embeddings for semantic similarity
- **OpenAI GPT** for patch synthesis and reranking
- **Bitbucket API** for diff ingestion

## 🚀 Quick Start

### Prerequisites
- Java 17 or later
- Maven 3.8+
- OpenAI API key (for LLM features)
- Bitbucket credentials (for diff processing)

### Installation
```bash
git clone <repository-url>
cd rag-retrofit
mvn clean install
```

### Basic Usage

1. **Build Index from Source Code**
```bash
java -jar cli/target/cli-1.0.0-SNAPSHOT.jar index /path/to/source/repo -o ./source-index
java -jar cli/target/cli-1.0.0-SNAPSHOT.jar index /path/to/target/repo -o ./target-index
```

2. **Test Search Functionality**
```bash
java -jar cli/target/cli-1.0.0-SNAPSHOT.jar search "UserService login method" -i ./target-index --openai-key YOUR_KEY
```

3. **Apply Changes from Bitbucket Diff**
```bash
java -jar cli/target/cli-1.0.0-SNAPSHOT.jar retrofit \
  "https://bitbucket.org/workspace/repo/commit/abc123" \
  /path/to/target/repo \
  --bitbucket-user username \
  --bitbucket-token token \
  --openai-key YOUR_KEY \
  -i ./target-index
```

## 📁 Project Structure

```
rag-retrofit/
├── indexer/              # Code parsing and chunking
│   └── src/main/java/com/ragretrofit/indexer/
│       ├── model/        # CodeChunk data models
│       └── parser/       # Java, JSP, Config parsers
├── stores/               # Storage implementations
│   └── src/main/java/com/ragretrofit/stores/
│       ├── lucene/       # BM25 search store
│       ├── vector/       # Vector similarity store  
│       └── graph/        # Code relationship graph
├── retrieval/            # Hybrid retrieval pipeline
│   └── src/main/java/com/ragretrofit/retrieval/
│       ├── HybridRetriever.java
│       ├── LLMReranker.java
│       └── CodeGraphFilter.java
├── retrofit/             # Change application logic
│   └── src/main/java/com/ragretrofit/retrofit/
│       ├── BitbucketDiffProcessor.java
│       ├── ASTTransformer.java
│       └── PatchSynthesizer.java
└── cli/                  # Command-line interface
    └── src/main/java/com/ragretrofit/cli/
        └── Main.java
```

## 🔧 Configuration

### Environment Variables
```bash
export OPENAI_API_KEY="your-openai-api-key"
export BITBUCKET_USER="your-bitbucket-username"  
export BITBUCKET_TOKEN="your-app-password"
```

### Index Configuration
The system creates several indices:
- **BM25 Index**: `./rag-index/bm25/` - Lucene index for lexical search
- **Vector Index**: `./rag-index/vectors.json` - Embeddings for semantic search
- **Graph Index**: `./rag-index/graph/` - Code relationship mappings

## 🎛 Command Reference

### Index Command
```bash
rag-retrofit index <source-path> [OPTIONS]
```
- `-o, --output`: Output directory for indices (default: ./rag-index)
- `-f, --force`: Force rebuild of existing indices
- `-t, --threads`: Number of processing threads (default: 4)
- `-v, --verbose`: Enable verbose logging

### Search Command  
```bash
rag-retrofit search <query> [OPTIONS]
```
- `-i, --index`: Path to index directory (default: ./rag-index)
- `-n, --num-results`: Number of results (default: 10)
- `-t, --type`: Filter by chunk type (METHOD, CLASS, JSP_FRAGMENT, etc.)
- `--openai-key`: OpenAI API key for LLM reranking

### Retrofit Command
```bash
rag-retrofit retrofit <bitbucket-url> <target-repo> [OPTIONS]
```
- `-i, --index`: Target repo index path
- `-o, --output`: Output directory for patches
- `--bitbucket-user`: Bitbucket username (required)
- `--bitbucket-token`: Bitbucket token (required)
- `--openai-key`: OpenAI API key (required)
- `--dry-run`: Generate patches without applying
- `--skip-validation`: Skip compilation validation

### Status Command
```bash
rag-retrofit status [OPTIONS]
```
- `-i, --index`: Index directory to check

## 📊 How It Works

### 1. Code Chunking Strategy
- **Method-level chunks** with full context (imports, class, package)
- **Sliding windows** for large methods (20-30% overlap)
- **JSP fragments** for UI components with MVC mappings
- **Config sections** for Struts/Spring configurations

### 2. Indexing Process
```java
// Example: Method chunk with metadata
CodeChunk chunk = CodeChunk.builder()
    .id("com.example.UserService.login#METHOD#123456")
    .content(methodCode)
    .type(CodeChunk.ChunkType.METHOD)
    .fullyQualifiedName("com.example.UserService.login")
    .apiCallSequence(Arrays.asList("validateCredentials", "createSession"))
    .mvcMapping(Map.of("actionPath", "/user/login"))
    .build();
```

### 3. Retrieval Pipeline
1. **BM25 Prefilter**: Fast lexical search (100-200 candidates)
2. **Graph Filter**: Relationship-aware filtering  
3. **Vector Recall**: Semantic similarity (top 50)
4. **LLM Reranking**: Context-aware final ranking (top 10)

### 4. Change Application
1. Parse Bitbucket diff into structured changes
2. Map source changes to target locations via retrieval
3. Generate AST transformations for deterministic edits
4. Use LLM for fuzzy/contextual changes
5. Validate compilation and run focused tests

## 🧪 Example Use Cases

### Scenario 1: API Signature Change
```java
// Source diff shows:
- public User login(String username, String password)
+ public User login(LoginRequest request)

// Pipeline finds equivalent method in target:
// com.company.auth.UserService.login(String, String)

// Generates AST transformation:
// 1. Change parameter signature
// 2. Update method body to extract username/password from request
// 3. Update callers to pass LoginRequest object
```

### Scenario 2: JSP Include Path Change
```jsp
<!-- Source diff shows: -->
- <%@ include file="/common/header.jsp" %>
+ <%@ include file="/shared/common/header.jsp" %>

<!-- Pipeline finds all JSPs with similar include -->
<!-- Updates paths consistently across target codebase -->
```

## 🔒 Security & Privacy

- **Local-first processing**: All parsing, indexing, and retrieval happens locally
- **Cloud LLM for reasoning only**: Only sends anonymized code patterns to OpenAI
- **No code storage in cloud**: Your source code never leaves your infrastructure
- **Configurable LLM providers**: Easy to swap OpenAI for local models

## 📈 Performance

### Typical Performance (1M LOC codebase)
- **Indexing**: ~30 minutes (4 threads)
- **Search**: <100ms for hybrid retrieval
- **Retrofit**: ~5-10 minutes per commit (depending on change size)

### Memory Usage
- **Indexing**: 2-4GB RAM
- **Runtime**: 1-2GB RAM  
- **Disk**: ~100MB indices per 100K LOC

## 🤝 Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)  
5. Open Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

For issues, questions, or contributions:
- **Issues**: GitHub Issues
- **Discussions**: GitHub Discussions
- **Documentation**: [Wiki](../../wiki)

## 🎉 Acknowledgments

- **LangChain4j** for excellent Java LLM integration
- **JavaParser** for robust AST manipulation
- **Apache Lucene** for high-performance search
- **OpenAI** for powerful language models
- **Bitbucket** for comprehensive diff APIs