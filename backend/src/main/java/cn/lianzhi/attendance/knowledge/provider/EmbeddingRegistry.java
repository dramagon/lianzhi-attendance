package cn.lianzhi.attendance.knowledge.provider;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;

import java.util.Properties;

public final class EmbeddingRegistry {
    static final String SERVICE = "lianzhi-embedding";

    private EmbeddingRegistry() {}

    public static NamingService connect(String address, String namespace, String user, String password)
            throws NacosException {
        if (address.isBlank()) throw new IllegalArgumentException("Nacos address is required");
        var properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, address);
        if (!namespace.isBlank()) properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        if (!user.isBlank()) {
            properties.setProperty(PropertyKeyConst.USERNAME, user);
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        return NacosFactory.createNamingService(properties);
    }

    static void validateToken(String token) {
        if (!token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException("Embedding worker requires a 32–256 character service token");
    }
}
