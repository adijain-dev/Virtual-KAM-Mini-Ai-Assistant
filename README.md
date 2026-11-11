# Snapdeal Virtual KAM (Disha)

An autonomous, voice-first AI Key Account Manager (KAM) for Snapdeal sellers experiencing sales drops. Disha analyzes seller metrics, diagnoses root causes via deterministic revenue bridging, asks targeted follow-ups, recommends high-impact actions, and commits to a saved action plan, follow-up call, and summary message.

---

## ⚡ Quickstart

### Prerequisites
- **Java 17+** & **Maven 3.9+**
- **Google Gemini API Key** ([Google AI Studio](https://aistudio.google.com/))
- **Google Chrome** or **Microsoft Edge** (for microphone access)

### Run Locally
```bash
# Set your API key
export GEMINI_API_KEY="your_api_key_here"       # Linux/macOS
$env:GEMINI_API_KEY="your_api_key_here"         # Windows PowerShell

# Start application
mvn spring-boot:run
```
Open **`http://localhost:8080`**, allow microphone permissions, and click **Call Disha**.

---

## 🛠️ Configuration & Models

| Environment Variable | Default | Purpose |
|----------------------|---------|---------|
| `GEMINI_API_KEY` | *(required)* | Google Gemini API Key |
| `GEMINI_MODEL` | `gemini-3.1-flash-lite` | LLM reasoning & tool execution |
| `GEMINI_STT_MODEL` | `gemini-3.1-flash-lite` | Audio transcription fallback |
| `GEMINI_TTS_MODEL` | `gemini-3.1-flash-tts-preview` | Cloud voice synthesis |
| `GEMINI_TTS_VOICE` | `Kore` | Default voice persona |
| `GEMINI_THINKING_BUDGET` | `-1` | `-1` omits thinkingConfig for fastest latency |

---

## 🏗️ Architecture & Call Flow

```
[Seller Speaks] ──> [Browser Web Speech API / Gemini STT] ──> [Spring Boot Agent]
                                                                      │
                                                ┌─────────────────────┴─────────────────────┐
                                                ▼                                           ▼
                                      [Deterministic Tools]                       [Gemini LLM]
                                      • Revenue bridge calculation               • Automatic language detection
                                      • Stock, price & traffic scoring           • Natural voice response
                                                │                                           │
                                                └─────────────────────┬─────────────────────┘
                                                                      ▼
[Spoken Response] <── [Instant Browser Voice / Gemini TTS] <── [Action Plan & State]
```

- **Grounded in Java**: Key metrics and root-cause diagnoses are computed deterministically in code (`ToolService.java`), preventing hallucinated figures.
- **Low Latency Pipeline**:
  - **Instant Voice**: Browser speech synthesis delivers replies in <100ms with zero API quota consumption.
  - **1-Shot Reasoning**: Pre-loads seller metrics into system context, cutting multi-round tool-calling delays.
  - **Cached Greeting**: Fixed bilingual greeting plays with 0ms network latency on call start.

---

## 📊 Seller Scenarios

| Seller Scenario | Primary Problem | Disha's Diagnosis & Action |
|-----------------|-----------------|----------------------------|
| **Rajesh Kumar** *(Default sample)* | Out-of-stock count jumped from 5 to 18 | Identifies stock-outs as driver of ~88% lost orders; investigates supplier delays. |
| **Anita Verma** *(Mock scenario)* | Average price increased by 12% | Pinpoints price uncompetitiveness vs. peers; recommends strategic repricing. |
| **Imran Qureshi** *(Mock scenario)* | Impressions plummeted by 36% | Diagnoses visibility collapse due to paused ads; advises re-enabling ads on winners. |

---

## 🚀 Cloud Deployment

The microphone requires `localhost` or an `https://` domain:

1. **Push repository to GitHub**:
   ```bash
   git init && git add . && git commit -m "feat: Virtual KAM release"
   git remote add origin https://github.com/<your-username>/virtual-kam.git
   git push -u origin main
   ```
2. **Deploy on Render / Railway**:
   - Create a new **Web Service** from your GitHub repo (auto-detects `Dockerfile`).
   - Add environment variable: `GEMINI_API_KEY = your_key`.
   - Access the live application via the provided `https://...` URL.
