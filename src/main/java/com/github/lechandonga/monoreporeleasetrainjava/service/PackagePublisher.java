package com.github.lechandonga.monoreporeleasetrainjava.service;

import com.github.lechandonga.monoreporeleasetrainjava.model.Version;

/**
 * 发布器：把一个包的指定版本发布出去。全部在本地完成。
 */
public interface PackagePublisher {

    /**
     * 发布单个包。同一 (batchId, packageName, version) 重复调用是幂等的：
     * 已成功发布过则直接返回，不会产生重复发布记录。
     *
     * @return true 表示本次真正执行了发布；false 表示发布记录已存在（幂等命中）
     */
    boolean publish(String batchId, String packageName, Version version);
}
