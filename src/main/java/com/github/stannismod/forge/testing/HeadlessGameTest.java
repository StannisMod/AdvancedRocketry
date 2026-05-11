package com.github.stannismod.forge.testing;

public interface HeadlessGameTest {

    String id();

    String category();

    boolean required();

    int timeoutTicks();

    void setUp(TestContext context) throws Exception;

    TestStatus tick(TestContext context) throws Exception;

    void tearDown(TestContext context) throws Exception;
}

