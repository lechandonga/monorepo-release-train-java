package com.github.lechandonga.monoreporeleasetrainjava.model;

/**
 * 一个包对另一个包的依赖声明：from 依赖 to，要求的版本区间为 versionRange。
 */
public record DependencySpec(String from, String to, String versionRange) {
}
