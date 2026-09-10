package com.example.chatbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * REST Controller cho UrChatbot Widget
 * Chuẩn kết nối: OpenAI Compatible API (OpenAI, Groq, Ollama, vLLM, DeepSeek, v.v.)
 * Tương thích: Spring Boot 2.x / 3.x, Java 8+
 * 
 * Endpoint duy nhất:
 * - POST /api/chat : Proxy streaming SSE tới OpenAI-compatible Chat Completions API
 *                    + Tự động inject knowledgeBase (nếu có từ frontend) vào System Prompt
 *                    + Cố định các thông số AI an toàn ở backend (temperature, max_tokens, top_p)
 *                    + Tích hợp function tool call `suggest_followup_questions` trong cùng 1 request
 *                    + Stream event SSE `data: {"__suggestions__": [...]}` trước `[DONE]`
 */
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.POST, RequestMethod.OPTIONS})
public class UrChatbotController {

    private static final double FIXED_TEMPERATURE = 0.6;
    private static final int FIXED_MAX_TOKENS = 1200;
    private static final double FIXED_TOP_P = 0.95;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    @Value("${openai.api.key:your_api_key_here}")
    private String apiKey;

    @Value("${openai.model:qwen/qwen3.8-27b}")
    private String defaultModel;

    // Cấu hình tool gợi ý câu hỏi tiếp theo (suggest_followup_questions)
    private static final ObjectNode SUGGEST_TOOL;

    static {
        ObjectMapper mapper = new ObjectMapper();
        SUGGEST_TOOL = mapper.createObjectNode();
        SUGGEST_TOOL.put("type", "function");
        ObjectNode fn = SUGGEST_TOOL.putObject("function");
        fn.put("name", "suggest_followup_questions");
        fn.put("description", "Generate 2-3 short, relevant follow-up questions based on the conversation. Always use the SAME LANGUAGE as the user's last message.");
        
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        ObjectNode questions = props.putObject("questions");
        questions.put("type", "array");
        questions.put("description", "List of 2-3 follow-up questions in the same language as the user");
        questions.putObject("items").put("type", "string");
        questions.put("minItems", 2);
        questions.put("maxItems", 3);
        
        ArrayNode req = params.putArray("required");
        req.add("questions");
    }

    /**
     * POST /api/chat
     * Tiếp nhận tin nhắn từ widget chatbot và stream câu trả lời về frontend
     */
    @PostMapping(value = "/api/chat", produces = "text/event-stream;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody Map<String, Object> payload) {

        // 1. Validate tin nhắn đầu vào
        Object messagesObj = payload.get("messages");
        if (!(messagesObj instanceof List) || ((List<?>) messagesObj).isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // 2. Chuyển messages sang JsonNode để xử lý inject knowledgeBase
        JsonNode messagesNode = objectMapper.valueToTree(messagesObj);
        ArrayNode messagesArray;
        if (messagesNode.isArray()) {
            messagesArray = (ArrayNode) messagesNode;
        } else {
            messagesArray = objectMapper.createArrayNode();
            messagesArray.add(messagesNode);
        }

        // 3. Xử lý Knowledge Base từ frontend (nếu có)
        Object kbObj = payload.get("knowledgeBase");
        String knowledgeBase = (kbObj != null) ? kbObj.toString().trim() : "";

        if (!knowledgeBase.isEmpty()) {
            injectKnowledgeBase(messagesArray, knowledgeBase);
        }

        // 4. Chuẩn bị request payload gửi tới OpenAI-compatible API với các tham số cố định ở Backend
        String model = (payload.get("model") != null && !payload.get("model").toString().trim().isEmpty())
                ? payload.get("model").toString().trim()
                : defaultModel;

        final ObjectNode requestPayload = objectMapper.createObjectNode();
        requestPayload.put("model", model);
        requestPayload.set("messages", messagesArray);
        requestPayload.put("temperature", FIXED_TEMPERATURE);
        requestPayload.put("max_completion_tokens", FIXED_MAX_TOKENS);
        requestPayload.put("top_p", FIXED_TOP_P);
        requestPayload.put("stream", true);
        
        // Bật function call tạo gợi ý câu hỏi kèm trong cùng lượt sinh
        ArrayNode toolsArr = requestPayload.putArray("tools");
        toolsArr.add(SUGGEST_TOOL);
        requestPayload.put("parallel_tool_calls", true);

        // 5. Khởi tạo StreamingResponseBody để stream dữ liệu real-time
        final String effectiveUrl = apiUrl;
        final String effectiveKey = apiKey;

        StreamingResponseBody responseStream = new StreamingResponseBody() {
            @Override
            public void writeTo(OutputStream outputStream) throws IOException {
                HttpURLConnection conn = null;
                try {
                    URL targetUrl = new URL(effectiveUrl);
                    conn = (HttpURLConnection) targetUrl.openConnection();
                    conn.setRequestMethod("POST");
                    if (effectiveKey != null && !effectiveKey.trim().isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + effectiveKey.trim());
                    }
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);

                    // Gửi request body tới AI endpoint
                    try (OutputStream os = conn.getOutputStream()) {
                        objectMapper.writeValue(os, requestPayload);
                        os.flush();
                    }

                    int statusCode = conn.getResponseCode();
                    if (statusCode >= 400) {
                        handleApiError(conn, statusCode, outputStream);
                        return;
                    }

                    // Đọc SSE stream từ AI endpoint và forward trực tiếp về frontend
                    processSseStream(conn.getInputStream(), outputStream);

                } catch (Exception e) {
                    sendSseError(outputStream, "Lỗi kết nối streaming: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/event-stream;charset=UTF-8"))
                .header("Cache-Control", "no-cache, no-transform")
                .header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(responseStream);
    }

    /**
     * Inject Knowledge Base vào System Prompt của cuộc trò chuyện
     */
    private void injectKnowledgeBase(ArrayNode messagesArray, String knowledgeBase) {
        String kbPrompt = "\n\n=== TÀI LIỆU & KIẾN THỨC BỔ TRỢ (KNOWLEDGE BASE) ===\n"
                + knowledgeBase
                + "\n===================================================\n"
                + "NGUYÊN TẮC: Hãy ưu tiên sử dụng kiến thức bổ trợ ở trên để trả lời đầy đủ, chính xác câu hỏi của người dùng.";

        boolean injected = false;
        for (int i = 0; i < messagesArray.size(); i++) {
            JsonNode msg = messagesArray.get(i);
            if (msg.has("role") && "system".equalsIgnoreCase(msg.get("role").asText())) {
                String existingContent = msg.has("content") ? msg.get("content").asText() : "";
                ((ObjectNode) msg).put("content", existingContent + kbPrompt);
                injected = true;
                break;
            }
        }

        // Nếu chưa có tin nhắn system nào trong danh sách messages, tạo mới ở đầu danh sách
        if (!injected) {
            ObjectNode newSystemMsg = objectMapper.createObjectNode();
            newSystemMsg.put("role", "system");
            newSystemMsg.put("content", "Bạn là trợ lý AI thông minh." + kbPrompt);
            messagesArray.insert(0, newSystemMsg);
        }
    }

    /**
     * Xử lý luồng SSE từ AI service:
     * 1. Forward content chunks tới client
     * 2. Tích luỹ delta của function call `suggest_followup_questions`
     * 3. Bắn event `{"__suggestions__": [...]}` trước `[DONE]`
     */
    private void processSseStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<Integer, ToolCallAccumulator>();

        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (!trimmed.startsWith("data:")) {
                outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                continue;
            }

            String dataStr = trimmed.substring(5).trim();
            if ("[DONE]".equals(dataStr)) {
                // Tạm giữ [DONE], xử lý tool call trước khi kết thúc
                continue;
            }

            try {
                JsonNode parsed = objectMapper.readTree(dataStr);
                JsonNode choices = parsed.get("choices");
                if (choices != null && choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null) {
                        // Tích luỹ tool_calls delta chunks
                        JsonNode toolCalls = delta.get("tool_calls");
                        if (toolCalls != null && toolCalls.isArray()) {
                            accumulateToolCalls(toolCalls, toolCallAccumulators);
                        }

                        // Forward content text chunk về client
                        if (delta.has("content") && !delta.get("content").isNull()) {
                            String forwardSse = "data: " + dataStr + "\n\n";
                            outputStream.write(forwardSse.getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                        }
                    }
                }
            } catch (Exception ex) {
                outputStream.write((line + "\n\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        }

        // Trích xuất và phát gợi ý câu hỏi tiếp theo (nếu có từ tool call)
        emitSuggestionsIfPresent(toolCallAccumulators, outputStream);

        // Báo kết thúc stream chuẩn OpenAI SSE
        outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private void accumulateToolCalls(JsonNode toolCalls, Map<Integer, ToolCallAccumulator> accumulators) {
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonNode tc = toolCalls.get(i);
            int idx = tc.has("index") ? tc.get("index").asInt() : 0;
            ToolCallAccumulator acc = accumulators.get(idx);
            if (acc == null) {
                acc = new ToolCallAccumulator();
                accumulators.put(idx, acc);
            }
            JsonNode fnNode = tc.get("function");
            if (fnNode != null) {
                if (fnNode.has("name")) {
                    acc.name.append(fnNode.get("name").asText());
                }
                if (fnNode.has("arguments")) {
                    acc.argumentsRaw.append(fnNode.get("arguments").asText());
                }
            }
        }
    }

    private void emitSuggestionsIfPresent(Map<Integer, ToolCallAccumulator> accumulators, OutputStream outputStream) {
        try {
            for (ToolCallAccumulator acc : accumulators.values()) {
                if ("suggest_followup_questions".equals(acc.name.toString()) && acc.argumentsRaw.length() > 0) {
                    JsonNode argsNode = objectMapper.readTree(acc.argumentsRaw.toString());
                    JsonNode questionsNode = argsNode.get("questions");
                    if (questionsNode != null && questionsNode.isArray() && questionsNode.size() > 0) {
                        List<String> questions = new ArrayList<String>();
                        for (int i = 0; i < questionsNode.size() && questions.size() < 3; i++) {
                            String q = questionsNode.get(i).asText().trim();
                            if (!q.isEmpty()) {
                                questions.add(q);
                            }
                        }

                        if (!questions.isEmpty()) {
                            ObjectNode suggestEvent = objectMapper.createObjectNode();
                            ArrayNode qArr = suggestEvent.putArray("__suggestions__");
                            for (String q : questions) {
                                qArr.add(q);
                            }
                            String ssePayload = "data: " + objectMapper.writeValueAsString(suggestEvent) + "\n\n";
                            outputStream.write(ssePayload.getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                        }
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("[UrChatbot] Lỗi phân tích gợi ý câu hỏi: " + ex.getMessage());
        }
    }

    private void handleApiError(HttpURLConnection conn, int statusCode, OutputStream outputStream) throws IOException {
        String errMsg = "Lỗi API (" + statusCode + ")";
        try (InputStream es = conn.getErrorStream()) {
            if (es != null) {
                JsonNode errNode = objectMapper.readTree(es);
                if (errNode.has("error") && errNode.get("error").has("message")) {
                    errMsg = errNode.get("error").get("message").asText();
                }
            }
        } catch (Exception ignored) {}

        sendSseError(outputStream, errMsg);
    }

    private void sendSseError(OutputStream outputStream, String errorMessage) {
        try {
            String sseError = "data: " + objectMapper.writeValueAsString(
                    Collections.singletonMap("error", errorMessage)) + "\n\n";
            outputStream.write(sseError.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (Exception ignored) {}
    }

    /**
     * Lớp hỗ trợ tích luỹ các mảnh delta của Tool Call
     */
    private static class ToolCallAccumulator {
        final StringBuilder name = new StringBuilder();
        final StringBuilder argumentsRaw = new StringBuilder();
    }
}
