package com.example.fakeclaude;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 对话配置类
@Configuration
public class ChatMemoryConfiguration {
    //  摘要提取
    @Bean
    SessionMemoryAdvisor sessionMemorySummarizeAdvisor(DeepSeekChatModel deepSeekChatModel) {
        SessionService service =
                DefaultSessionService.builder().sessionRepository(
                        InMemorySessionRepository.builder().build()
                ).build();

        return SessionMemoryAdvisor.builder(service)
                .defaultUserId("nmy")
                .compactionTrigger(new TurnCountTrigger(5))
                .compactionStrategy(RecursiveSummarizationCompactionStrategy.builder(
                        ChatClient.builder(deepSeekChatModel).build())
                        .maxEventsToKeep(5) // 保留不压缩的信息数
                        .overlapSize(0) // 出压缩外额外的条数
                        .build())
                .build();
    }
}
