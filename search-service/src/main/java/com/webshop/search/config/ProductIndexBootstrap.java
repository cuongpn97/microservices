package com.webshop.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quản lý index theo kiểu blue/green:
 * <ul>
 *   <li>GHI luôn vào index thật ({@code products-v3}); ĐỌC luôn qua alias ({@code products}).</li>
 *   <li>Khởi động với index mới (đổi mapping): tạo index rỗng, consumer group mới replay topic vào đó, alias vẫn trỏ
 *       index cũ → người dùng vẫn tìm được đầy đủ. Khi listener báo idle (đã đọc hết topic) mới chuyển alias, nguyên tử.</li>
 *   <li>Chưa có alias (cài mới): gắn alias ngay — không có gì để bảo vệ.</li>
 * </ul>
 * Chạy TRƯỚC Kafka listener ({@link com.webshop.search.repository.ProductIndexRepository}
 * phụ thuộc bean này): nếu listener ghi trước, Elasticsearch tự tạo
 * index với dynamic mapping (sai analyzer, sai kiểu).
 */
@Slf4j
@Component
public class ProductIndexBootstrap implements InitializingBean {

    private final ElasticsearchClient client;
    private final SearchProperties props;
    private final AtomicBoolean aliasReady = new AtomicBoolean();

    public ProductIndexBootstrap(ElasticsearchClient client, SearchProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        createIndexIfMissing();
        Set<String> current = aliasTargets();
        if (current.equals(Set.of(props.indexName()))) {
            aliasReady.set(true);
            log.info("Alias {} already points to {}", props.indexAlias(), props.indexName());
        } else if (current.isEmpty()) {
            switchAlias();
        } else {
            log.info("Alias {} still points to {}; rebuilding {} from Kafka before switching", props.indexAlias(),
                    current, props.indexName());
        }
    }

    /**
     * Listener không còn record nào để đọc → index mới đã bắt kịp topic → chuyển alias (một lần).
     * Chỉ đúng khi consumer group là group MỚI (đọc từ đầu topic): nếu group đã có offset, "idle" chỉ nghĩa là không có
     * thay đổi mới, và alias sẽ chuyển sang một index rỗng — xem ProductCdcListener (idIsGroup = false).
     */
    @EventListener
    public void onListenerIdle(ListenerContainerIdleEvent event) throws IOException {
        if (!aliasReady.get()) {
            switchAlias();
        }
    }

    public boolean isAliasReady() {
        return aliasReady.get();
    }

    private void createIndexIfMissing() throws IOException {
        if (client.indices().exists(e -> e.index(props.indexName())).value()) {
            return;
        }
        try (InputStream mapping = new ClassPathResource("elasticsearch/" + props.indexName() + ".json")
                .getInputStream()) {
            client.indices().create(c -> c.index(props.indexName()).withJson(mapping));
            log.info("Created index {}", props.indexName());
        } catch (ElasticsearchException e) {
            // Hai instance khởi động cùng lúc: instance kia đã tạo trước → coi như thành công.
            if (!"resource_already_exists_exception".equals(e.error().type())) {
                throw e;
            }
        }
    }

    private Set<String> aliasTargets() throws IOException {
        if (!client.indices().existsAlias(a -> a.name(props.indexAlias())).value()) {
            return Set.of();
        }
        return client.indices().getAlias(a -> a.name(props.indexAlias())).result().keySet();
    }

    private synchronized void switchAlias() throws IOException {
        if (aliasReady.get()) {
            return;
        }
        Set<String> old = aliasTargets();
        // Một request _aliases = nguyên tử: không có khoảnh khắc nào alias trỏ vào 0 hoặc 2 index.
        client.indices().updateAliases(u -> {
            old.stream().filter(i -> !i.equals(props.indexName()))
                    .forEach(i -> u.actions(a -> a.remove(r -> r.index(i).alias(props.indexAlias()))));
            return u.actions(a -> a.add(ad -> ad.index(props.indexName()).alias(props.indexAlias())));
        });
        aliasReady.set(true);
        log.info("Alias {} now points to {} (was {}). Old index can be deleted once verified.", props.indexAlias(),
                props.indexName(), old);
    }
}
