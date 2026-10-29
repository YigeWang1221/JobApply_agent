package jobapply.config;

import jobapply.memory.ExperienceIndex;
import jobapply.memory.ExperienceModel;
import jobapply.persistence.StorageLayout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 跨工作台的经验检索（C022）：本地嵌入模型和向量索引。启动时不下载、不在启动线程上做嵌入；模型已在本地时索引在后台
 * 对一遍。还没选数据位置（C017）时不启动索引，免得在默认位置建文件夹。
 */
@Configuration
public class RagConfiguration {

    @Bean(destroyMethod = "close")
    ExperienceModel experienceModel(StorageLayout layout,
                                    @Value("${jobapply.rag.model-url:" + ExperienceModel.DEFAULT_BASE_URL + "}")
                                    String modelUrl) {
        return new ExperienceModel(layout.ragModelDir(), modelUrl);
    }

    @Bean(destroyMethod = "close")
    ExperienceIndex experienceIndex(StorageLayout layout, ExperienceModel model, StorageLocation location) {
        ExperienceIndex index = new ExperienceIndex(layout, model);
        if (!location.needsChoice()) {
            index.start();
        }
        return index;
    }
}
