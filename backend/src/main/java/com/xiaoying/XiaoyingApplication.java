package com.xiaoying;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class XiaoyingApplication {

    public static void main(String[] args) {
        // 数据库 DATETIME(3) 存 UTC；JVM 默认时区与 JDBC 连接时区都固定为 UTC（architecture §7）。
        // 容器 / spring-boot:run 还会额外传入 -Duser.timezone=UTC，这里兜底以防直接 java -jar 启动。
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(XiaoyingApplication.class, args);
    }
}
