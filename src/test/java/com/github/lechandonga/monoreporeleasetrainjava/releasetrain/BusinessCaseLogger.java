package com.github.lechandonga.monoreporeleasetrainjava.releasetrain;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 用例执行前后输出业务覆盖点与结果，便于从测试日志看出覆盖了哪些业务。 */
public class BusinessCaseLogger implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-CASES");

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info("▶ 开始业务用例 [{}] {}",
                context.getRequiredTestClass().getSimpleName(),
                context.getDisplayName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        boolean failed = context.getExecutionException().isPresent();
        log.info("{} 用例结果 [{}] {} => {}", failed ? "✗" : "✔",
                context.getRequiredTestClass().getSimpleName(),
                context.getDisplayName(), failed ? "失败" : "通过");
    }
}
