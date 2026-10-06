package com.github.lechandonga.monoreporeleasetrainjava.releasetrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service.BatchStore;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service.LocalFilePublisher;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service.PackagePublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** 发布列车本地组件装配：JSON 存储 + 本地文件发布器，全部可在单机离线运行。 */
@Configuration
public class ReleaseTrainConfig {

    @Bean
    public ObjectMapper releaseTrainObjectMapper() {
        return JsonMapper.builder()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .findAndAddModules()
                .build();
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictDuplicateDetectionCustomizer() {
        return builder -> builder.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Bean
    public Path releaseTrainDataDir(@Value("${release-train.data-dir:./release-train-data}") String dir) {
        return Path.of(dir).toAbsolutePath().normalize();
    }

    @Bean
    public BatchStore batchStore(Path releaseTrainDataDir, ObjectMapper releaseTrainObjectMapper) {
        return new BatchStore(releaseTrainDataDir, releaseTrainObjectMapper);
    }

    @Bean
    public PackagePublisher packagePublisher(Path releaseTrainDataDir) {
        return new LocalFilePublisher(releaseTrainDataDir.resolve("registry"));
    }
}
