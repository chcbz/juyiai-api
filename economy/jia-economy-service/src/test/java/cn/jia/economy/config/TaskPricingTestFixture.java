package cn.jia.economy.config;

import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.impl.DatabaseTaskPricingService;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic catalog data shared by existing real-transaction fixtures. */
public final class TaskPricingTestFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TaskPricingTestFixture() { }

    public static DatabaseTaskPricingService install(JdbcTemplate jdbc, EconomyScope scope, List<ModelTokenPrice> prices) {
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-price-catalog.sql")).execute(jdbc.getDataSource());
        for (var price : prices) {
            insertVersion(jdbc, scope, price);
            jdbc.update("INSERT INTO economy_model_price_current VALUES(?,?,?,?,?,?,?)", scope.tenantId(), scope.clientId(),
                    price.channel(), price.model(), price.tier(), price.version(), 1L);
            jdbc.update("INSERT INTO economy_task_model_tariff VALUES(?,?,?,?,?,?,?)", scope.tenantId(), scope.clientId(),
                    price.model(), price.channel(), price.tier(), "[]", 1L);
        }
        return service(jdbc);
    }

    public static DatabaseTaskPricingService service(JdbcTemplate jdbc) {
        try {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(EconomyPriceCatalogMapper.class);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(jdbc.getDataSource());
            factory.setConfiguration(config);
            return new DatabaseTaskPricingService(new SqlSessionTemplate(factory.getObject()).getMapper(EconomyPriceCatalogMapper.class), JSON);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static void insertVersion(JdbcTemplate jdbc, EconomyScope scope, ModelTokenPrice price) {
        jdbc.update("INSERT INTO economy_model_price VALUES(?,?,?,?,?,?,?,?)", scope.tenantId(), scope.clientId(),
                price.channel(), price.model(), price.tier(), price.version(), modelJson(price), 1L);
    }

    public static String modelJson(ModelTokenPrice price) {
        var data = new LinkedHashMap<String, Object>();
        data.put("basis", price.basis());
        data.put("yuanPerMillionTokens", strings(price.yuanPerMillionTokens()));
        data.put("shortContextMaxInputTokens", price.shortContextMaxInputTokens());
        data.put("longContextYuanPerMillionTokens", strings(price.longContextYuanPerMillionTokens()));
        return JSON.writeValueAsString(data);
    }

    private static Map<String, String> strings(Map<ModelTokenPrice.Category, java.math.BigDecimal> rates) {
        var result = new LinkedHashMap<String, String>();
        rates.forEach((key, value) -> result.put(key.name(), value.toPlainString()));
        return result;
    }
}
