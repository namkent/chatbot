/**
 * Server API Service cho UrChatbot Widget
 * Chuẩn kết nối: OpenAI Compatible Streaming Chat API
 * Chạy độc lập trên Node.js 12+ (CommonJS, thuần built-in modules, không phụ thuộc thư viện ngoài).
 *
 * Endpoint duy nhất:
 * - POST /api/chat: stream SSE + tool_call suggest_followup_questions + inject knowledgeBase
 */

const http = require('http');
const https = require('https');
const url = require('url');
const fs = require('fs');
const path = require('path');
const { StringDecoder } = require('string_decoder');

// Tự động đọc và nạp biến môi trường từ file .env (nếu có)
function loadEnv() {
  const envPath = path.resolve(__dirname, '.env');
  if (!fs.existsSync(envPath)) return;
  try {
    const content = fs.readFileSync(envPath, 'utf8');
    content.split(/\r?\n/).forEach(function (line) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) return;
      const eqIdx = trimmed.indexOf('=');
      if (eqIdx !== -1) {
        const key = trimmed.slice(0, eqIdx).trim();
        let val = trimmed.slice(eqIdx + 1).trim();
        if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
          val = val.slice(1, -1);
        }
        if (process.env[key] === undefined) {
          process.env[key] = val;
        }
      }
    });
  } catch (err) {
    console.warn('[Env] Không thể đọc file .env:', err.message);
  }
}

loadEnv();

const PORT = process.env.PORT || 3001;
const OPENAI_API_URL = process.env.OPENAI_API_URL || 'https://api.groq.com/openai/v1/chat/completions';
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || '';
const DEFAULT_MODEL = process.env.OPENAI_MODEL || 'qwen/qwen3.8-27b';

// Fixed backend AI generation parameters
const FIXED_TEMPERATURE = 0.6;
const FIXED_MAX_TOKENS = 1200;
const FIXED_TOP_P = 0.95;

// Tool definition cho suggest follow-up questions
const SUGGEST_TOOL = {
  type: 'function',
  function: {
    name: 'suggest_followup_questions',
    description: 'Generate 2-3 short, relevant follow-up questions based on the conversation. Always use the SAME LANGUAGE as the user\'s last message.',
    parameters: {
      type: 'object',
      properties: {
        questions: {
          type: 'array',
          description: 'List of 2-3 follow-up questions in the same language as the user',
          items: { type: 'string' },
          minItems: 2,
          maxItems: 3
        }
      },
      required: ['questions']
    }
  }
};

// Hàm gửi phản hồi JSON chuẩn có hỗ trợ CORS
function sendJson(res, statusCode, data) {
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization'
  });
  res.end(JSON.stringify(data));
}

// Xử lý đọc body của request
function getRequestBody(req) {
  return new Promise(function (resolve, reject) {
    let body = '';
    req.on('data', function (chunk) {
      body += chunk;
      if (body.length > 2 * 1024 * 1024) {
        reject(new Error('Payload too large'));
      }
    });
    req.on('end', function () {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch (err) {
        reject(new Error('Invalid JSON format'));
      }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async function (req, res) {
  // 1. CORS Preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Access-Control-Max-Age': '86400'
    });
    res.end();
    return;
  }

  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;

  try {
    // 2. Endpoint Chat Proxy Streaming + Tool Call suggest: POST /api/chat
    if (req.method === 'POST' && pathname === '/api/chat') {
      const payload = await getRequestBody(req);
      const messages = payload.messages || [];

      if (!Array.isArray(messages) || messages.length === 0) {
        return sendJson(res, 400, { error: 'Tham số `messages` là bắt buộc và phải là mảng không rỗng.' });
      }

      // Xử lý inject knowledgeBase từ frontend (nếu có)
      const knowledgeBase = (payload.knowledgeBase && typeof payload.knowledgeBase === 'string')
        ? payload.knowledgeBase.trim()
        : '';

      if (knowledgeBase) {
        const kbPrompt = '\n\n=== TÀI LIỆU & KIẾN THỨC BỔ TRỢ (KNOWLEDGE BASE) ===\n' + knowledgeBase + '\n===================================================\nNGUYÊN TẮC: Hãy ưu tiên sử dụng kiến thức bổ trợ ở trên để trả lời đầy đủ, chính xác câu hỏi của người dùng.';
        const sysMsg = messages.find(function (m) { return m.role === 'system'; });
        if (sysMsg) {
          sysMsg.content += kbPrompt;
        } else {
          messages.unshift({ role: 'system', content: 'Bạn là trợ lý AI thông minh.' + kbPrompt });
        }
      }

      const model = payload.model || DEFAULT_MODEL;

      const requestBody = JSON.stringify({
        model: model,
        messages: messages,
        temperature: FIXED_TEMPERATURE,
        max_completion_tokens: FIXED_MAX_TOKENS,
        top_p: FIXED_TOP_P,
        stream: true,
        tools: [SUGGEST_TOOL],
        parallel_tool_calls: true
      });

      const targetUrl = new url.URL(OPENAI_API_URL);
      const isHttps = targetUrl.protocol === 'https:';
      const client = isHttps ? https : http;

      const reqHeaders = {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(requestBody)
      };
      if (OPENAI_API_KEY) {
        reqHeaders['Authorization'] = 'Bearer ' + OPENAI_API_KEY;
      }

      const reqOptions = {
        protocol: targetUrl.protocol,
        hostname: targetUrl.hostname,
        port: targetUrl.port || (isHttps ? 443 : 80),
        path: targetUrl.pathname + (targetUrl.search || ''),
        method: 'POST',
        headers: reqHeaders
      };

      const proxyReq = client.request(reqOptions, function (apiRes) {
        const statusCode = apiRes.statusCode || 500;

        // Nếu upstream trả mã lỗi HTTP
        if (statusCode < 200 || statusCode >= 300) {
          let errBody = '';
          apiRes.on('data', function (chunk) { errBody += chunk; });
          apiRes.on('end', function () {
            let errMsg = 'Lỗi API (' + statusCode + ')';
            try {
              const errJson = JSON.parse(errBody);
              if (errJson.error && errJson.error.message) {
                errMsg = errJson.error.message;
              }
            } catch (e) {}
            sendJson(res, statusCode, { error: errMsg });
          });
          return;
        }

        // Bắt đầu SSE stream về client
        res.writeHead(200, {
          'Content-Type': 'text/event-stream; charset=utf-8',
          'Cache-Control': 'no-cache, no-transform',
          'Connection': 'keep-alive',
          'Access-Control-Allow-Origin': '*'
        });

        const decoder = new StringDecoder('utf8');
        let buffer = '';
        const toolCallAccumulator = {};

        apiRes.on('data', function (chunk) {
          buffer += decoder.write(chunk);
          const lines = buffer.split('\n');
          buffer = lines.pop(); // giữ lại phần chưa kết thúc dòng

          for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            const trimmed = line.trim();
            if (!trimmed) continue;

            // Pass-through các dòng không phải data:
            if (!trimmed.startsWith('data:')) {
              res.write(line + '\n');
              continue;
            }

            const dataStr = trimmed.replace(/^data:\s*/, '');
            if (dataStr === '[DONE]') {
              // Chưa kết thúc stream ngay để còn gửi tool suggestions nếu có
              continue;
            }

            try {
              const parsed = JSON.parse(dataStr);
              const choice = parsed.choices && parsed.choices[0];
              const delta = choice && choice.delta;

              // Thu thập tool_call delta chunks
              if (delta && delta.tool_calls) {
                for (let j = 0; j < delta.tool_calls.length; j++) {
                  const tc = delta.tool_calls[j];
                  const idx = tc.index !== undefined ? tc.index : 0;
                  if (!toolCallAccumulator[idx]) {
                    toolCallAccumulator[idx] = { name: '', argumentsRaw: '' };
                  }
                  if (tc.function && tc.function.name) {
                    toolCallAccumulator[idx].name += tc.function.name;
                  }
                  if (tc.function && tc.function.arguments) {
                    toolCallAccumulator[idx].argumentsRaw += tc.function.arguments;
                  }
                }
              }

              // Forward content chunk về client nếu có nội dung
              if (delta && delta.content !== undefined && delta.content !== null) {
                res.write('data: ' + dataStr + '\n\n');
              }
            } catch (e) {
              // JSON fragment hoặc định dạng khác — forward nguyên dòng
              res.write(line + '\n');
            }
          }
        });

        apiRes.on('end', function () {
          buffer += decoder.end();

          // Parse kết quả từ tool call để gửi gợi ý câu hỏi tiếp theo
          try {
            const keys = Object.keys(toolCallAccumulator);
            for (let k = 0; k < keys.length; k++) {
              const tc = toolCallAccumulator[keys[k]];
              if (tc.name === 'suggest_followup_questions' && tc.argumentsRaw) {
                const args = JSON.parse(tc.argumentsRaw);
                const questions = Array.isArray(args.questions)
                  ? args.questions.slice(0, 3).map(function (q) { return String(q).trim(); }).filter(Boolean)
                  : [];

                if (questions.length > 0) {
                  res.write('data: ' + JSON.stringify({ __suggestions__: questions }) + '\n\n');
                }
              }
            }
          } catch (e) {
            console.error('[Suggest Tool Parse Error]:', e.message);
          }

          res.write('data: [DONE]\n\n');
          res.end();
        });
      });

      proxyReq.on('error', function (err) {
        console.error('[Upstream API Error]:', err.message);
        if (!res.headersSent) {
          sendJson(res, 502, { error: 'Không thể kết nối đến AI API: ' + (err.message || 'Lỗi mạng') });
        } else {
          res.end();
        }
      });

      // Nếu client ngắt kết nối giữa chừng (ví dụ user bấm hủy hoặc đóng tab)
      req.on('close', function () {
        if (!proxyReq.finished) {
          proxyReq.abort();
        }
      });

      proxyReq.write(requestBody);
      proxyReq.end();
      return;
    }

    // Không tìm thấy route phù hợp
    return sendJson(res, 404, { error: 'Route không tồn tại' });
  } catch (err) {
    console.error('[Server API Error]:', err);
    return sendJson(res, 500, { error: err.message || 'Lỗi xử lý máy chủ' });
  }
});

server.listen(PORT, '0.0.0.0', function () {
  console.log('====================================================');
  console.log(`🚀 UrChatbot API Service (OpenAI-compatible) đang chạy tại port: ${PORT}`);
  console.log(`⚡ API Chat Streaming: http://localhost:${PORT}/api/chat`);
  console.log('====================================================');
});
