package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service;

/**
 * 包发布出口。默认实现为完全本地的原子文件落盘，不依赖任何外部服务或凭据。
 */
public interface PackagePublisher {

    /**
     * 发布指定版本。实现必须保证：成功才可见，失败不留半成品；
     * 对同一个 包+版本 的重复调用天然幂等。
     */
    void publish(String packageName, String version);
}
