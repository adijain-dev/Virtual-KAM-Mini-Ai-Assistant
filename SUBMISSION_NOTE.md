# Snapdeal Virtual KAM: submission note

**Candidate:** [Your name]  **Demo link:** [paste link]  **Recording:** [paste link]

## What I built

Disha is a voice-first Virtual KAM. A seller presses Call, and Disha greets them and asks how she can help, then waits. Once the seller explains the problem she checks their data, shares the most important finding (out-of-stock products jumped from 5 to 18) and asks one question. She then asks 3 or 4 targeted follow-ups, explains the cause, recommends 2 to 3 actions, and closes by saving an action plan, booking a follow-up call and sending a written summary (mock). She detects the seller's language by herself and replies in it: English, Hindi or Hinglish.

## Models and voice technology

| Part | Technology |
|---|---|
| Reasoning and conversation | Google Gemini (`gemini-2.5-flash`) with function calling |
| Speech to text | Gemini audio understanding, with automatic language detection; the browser records each utterance and detects the end of the seller's turn |
| Text to speech | Gemini TTS (`gemini-2.5-flash-preview-tts`), browser voice as fallback |
| Backend | Java 17, Spring Boot 3, plain REST calls to Gemini |
| Frontend | One HTML/JS page, no framework |

## Workflow

The call opens with a fixed bilingual greeting. When the seller speaks, the browser records the utterance and Gemini transcribes it in the language spoken. Spring Boot then sends the text to Gemini with the system prompt and tool list. Gemini calls tools (seller metrics, sales-drop diagnosis, cancellation breakdown, out-of-stock list, action plan, follow-up, summary). The tools run in Java on mock data, and Gemini turns the results into a short spoken reply, which Gemini TTS converts to audio.

## Key decisions

1. **Numbers come from code, not the model.** The diagnosis is a revenue bridge computed in Java: of the net sales drop, about 88% is fewer orders and about 12% is higher cancellations, and price contributes nothing. The LLM only explains it. This prevents invented figures.
2. **Works for different problems, not one story.** The brief's seller is the default scenario. The other sellers are my own mock data to show the logic adapts. The diagnosis scores three causes (stock-outs, pricing, visibility) by how much of the order decline each explains, and Disha asks questions and recommends actions that fit the cause found.
3. **Agent, not Q&A bot.** Disha leads with an insight, runs her own analysis, and ends with commitments (saved plan, follow-up date, message sent). The seller does not have to know what to ask.
4. **Short spoken turns.** The prompt limits replies to a few sentences, one question at a time, no lists or symbols, and numbers said the way people say them.
5. **Language is detected automatically.** Gemini transcribes whatever language is spoken, and the system prompt tells the model to reply in the language and script of the seller's last message. There is no language setting.
6. **Honest about gaps.** She does not guess traffic, ratings or competitor prices. She asks the seller.

## Assumptions

- Sales = orders x average selling price, so orders are gross; cancellation rate is measured against gross orders.
- The cancellation reasons, the list of 18 out-of-stock products and the lost-order estimates are mock data I created. Everything else for the default seller is from the brief.
- The two extra sellers (Anita Verma, Imran Qureshi) and their impressions, competitor prices, ad status and search rank are entirely mock data I created. The brief's seller is the default scenario; the others are only to show the logic adapts.
- No traffic, ratings, returns, ad-spend or competitor data is available.

## Limits and what I would do next

- Each turn adds a transcription call, so there is a short delay, and the silence detection may need tuning in a noisy room.
- Turn-taking has a short delay and no true barge-in. Next step: a realtime speech-to-speech API.
- Connect real order, inventory and ads data; send real WhatsApp messages; call back automatically at the follow-up date; measure whether the seller restocked and whether sales recovered.
