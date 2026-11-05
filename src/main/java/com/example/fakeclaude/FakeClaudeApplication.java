package com.example.fakeclaude;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import java.util.Scanner;


@SpringBootApplication
public class FakeClaudeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeClaudeApplication.class, args);
    }

    @Bean
    CommandLineRunner commandLineRunner(DeepSeekChatModel deepSeekChatModel,
                                        SessionMemoryAdvisor sessionMemorySummarizeAdvisor,
                                        ToolService toolService,
                                        ToolCallbackProvider callbackProvider) {
        return args -> {
            System.out.println("我是fake-Claude,你的屎山小助手：");

            //  可动态修改模型参数
            ChatClient chatClient = ChatClient.builder(deepSeekChatModel)
                    .defaultSystem("你是 fake-Claude，一个运行在用户本机的命令行助手，底层由 DeepSeek 模型提供能力。"
                            + "别人问你是谁时，就回答 fake-Claude。"
                            + "不要声称自己是 Claude、ChatGPT、DeepSeek或任何公司官方产品。也不要强调这句话")
                    // 工具：让模型能真正调用本机 shell
                    .defaultTools(
                            ShellTools.builder().build(),
                            FileSystemTools.builder().build()
                    )
                    .defaultTools(callbackProvider)
                    .defaultAdvisors(sessionMemorySummarizeAdvisor, SimpleLoggerAdvisor.builder().build())
                    .build();

            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("> 你：");
                    if (!scanner.hasNextLine()) {
                        break;
                    }
                    String message = scanner.nextLine().trim();
                    if ("exit".equalsIgnoreCase(message)) {
                        break;
                    }
                    if (message.isEmpty()) {
                        continue;
                    }
                    System.out.print("\n> AI：");

                    Flux<String> content = chatClient.prompt()
                            .user(message)
                            .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "nmy"))
                            .stream().content();

                    try {
                        content.doOnNext(System.out::print)
                                .blockLast();
                        System.out.println();
                    } catch (Exception e) {
                        System.out.println("\n[调用失败] " + e.getMessage());
                    }

                }
            }

        };
    }
}
