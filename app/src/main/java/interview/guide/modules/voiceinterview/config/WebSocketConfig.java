/**
 * WebSocket 端点注册配置
 *
 * 将 VoiceInterviewWebSocketHandler 注册到 /ws/voice-interview/{sessionId} 路径，
 * 配置 CORS 允许来源和 HttpSession 握手拦截器，使前端可通过 WebSocket 进行实时语音面试。
 *
 * 联动关系：
 * - 依赖 VoiceInterviewWebSocketHandler（核心 WebSocket 处理器）
 * - 依赖 CorsProperties（读取允许的跨域来源）
 * - 被 Spring WebSocket 框架自动加载
 */
package interview.guide.modules.voiceinterview.config;

import interview.guide.common.config.CorsProperties;
import interview.guide.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

/**
 * WebSocket 配置类
 * 配置语音面试的 WebSocket 端点和跨域策略
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * 语音面试 WebSocket 处理器
     * 负责处理所有语音面试的实时双向通信
     */
    private final VoiceInterviewWebSocketHandler voiceInterviewWebSocketHandler;

    /**
     * CORS 跨域配置
     * 用于配置允许访问 WebSocket 的前端来源
     */
    private final CorsProperties corsProperties;

    /**
     * 注册语音面试 WebSocket 端点及握手策略。
     *
     * 由 Spring WebSocket 启动阶段调用。把 Handler 绑定到
     * {@code /ws/voice-interview/{sessionId}}，通过握手拦截器传递 HTTP Session 属性，并从集中式
     * CORS 配置解析允许来源；连接建立后的业务生命周期交给 Handler。
     *
     * @param registry Spring WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 注册 WebSocket 处理器到 /ws/voice-interview/{sessionId} 路径
        // 添加 HttpSession 握手拦截器，用于传递用户会话信息
        // 配置允许的跨域来源，逗号分隔列表
        registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                .setAllowedOrigins(corsProperties.getAllowedOrigins().split(","));
    }
}
