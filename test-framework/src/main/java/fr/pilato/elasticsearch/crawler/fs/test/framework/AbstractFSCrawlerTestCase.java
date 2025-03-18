/*
 * Licensed to David Pilato (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.pilato.elasticsearch.crawler.fs.test.framework;

import com.carrotsearch.randomizedtesting.RandomizedContext;
import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.carrotsearch.randomizedtesting.ThreadFilter;
import com.carrotsearch.randomizedtesting.annotations.*;
import com.carrotsearch.randomizedtesting.generators.RandomNumbers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Supplier;

import static com.carrotsearch.randomizedtesting.RandomizedTest.randomLocale;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomTimeZone;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.fail;

@RunWith(RandomizedRunner.class)
@Listeners({FSCrawlerReproduceInfoPrinter.class})
@ThreadLeakScope(ThreadLeakScope.Scope.SUITE)
@ThreadLeakLingering(linger = 5000) // 5 sec lingering
@ThreadLeakFilters(filters = {
        AbstractFSCrawlerTestCase.TestContainerThreadFilter.class,
        AbstractFSCrawlerTestCase.JNACleanerThreadFilter.class
})
public abstract class AbstractFSCrawlerTestCase {

    public static class TestContainerThreadFilter implements ThreadFilter {
        @Override
        public boolean reject(Thread t) {
            return
                    t.getName().startsWith("ducttape-") ||
                    t.getThreadGroup() != null && "testcontainers".equals(t.getThreadGroup().getName());
        }
    }

    public static class JNACleanerThreadFilter implements ThreadFilter {
        @Override
        public boolean reject(Thread t) {
            return "JNA Cleaner".equals(t.getName());
        }
    }

    private static final Logger logger = LogManager.getLogger();
    private static final String RANDOM = "random";

    @Rule
    public TestName name = new TestName();

    @ClassRule
    public static final TemporaryFolder folder = new TemporaryFolder();
    protected static Path rootTmpDir;

    @BeforeClass
    public static void createTmpDir() throws IOException {
        folder.create();
        rootTmpDir = Paths.get(folder.getRoot().toURI());
    }

    private static final Locale savedLocale = Locale.getDefault();
    private static final TimeZone savedTimeZone = TimeZone.getDefault();

    @BeforeClass
    public static void setLocale() {
        String testLocale = getSystemProperty("tests.locale", RANDOM);
        Locale locale = testLocale.equals(RANDOM) ? randomLocale() : new Locale.Builder().setLanguageTag(testLocale).build();
        logger.debug("Running test suite with Locale [{}]", locale);
        Locale.setDefault(locale);
    }

    @AfterClass
    public static void resetLocale() {
        Locale.setDefault(savedLocale);
    }

    @BeforeClass
    public static void setTimeZone() {
        String testTimeZone = getSystemProperty("tests.timezone", RANDOM);
        TimeZone timeZone = testTimeZone.equals(RANDOM) ? randomTimeZone() : TimeZone.getTimeZone(testTimeZone);
        logger.debug("Running test suite with TimeZone [{}]/[{}]", timeZone.getID(), timeZone.getDisplayName());
        TimeZone.setDefault(timeZone);
    }

    @AfterClass
    public static void resetTimeZone() {
        TimeZone.setDefault(savedTimeZone);
    }

    protected String getCurrentTestName() {
        return toUnderscoreCase(name.getMethodName());
    }

    protected String getCurrentClassName() {
        return toUnderscoreCase(this.getClass().getSimpleName());
    }

    public static int between(int min, int max) {
        return RandomNumbers.randomIntBetween(RandomizedContext.current().getRandom(), min, max);
    }

    public static String toUnderscoreCase(String value) {
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c)) {
                if (!changed) {
                    sb.setLength(0);
                    // copy it over here
                    for (int j = 0; j < i; j++) {
                        sb.append(value.charAt(j));
                    }
                    changed = true;
                    if (i != 0) {
                        sb.append('_');
                    }
                } else {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                if (changed) {
                    sb.append(c);
                }
            }
        }
        if (!changed) {
            return value;
        }
        return sb.toString();
    }

    public static File URLtoFile(URL url) {
        try {
            return new File(url.toURI());
        } catch(URISyntaxException e) {
            return new File(url.getPath());
        }
    }

    /**
     * Helper to check that something actually throws an error
     * @param exceptionClass    Expected error
     * @param function          Function to be executed
     */
    public static <T extends Throwable> T expectThrows(Class<T> exceptionClass, Supplier<?> function) {
        try {
            Object o = function.get();
            fail("We should have caught a " + exceptionClass.getName() + ". " +
                    "But we returned " + o + ".");
        } catch (Throwable t) {
            assertThat(t, instanceOf(exceptionClass));
            //noinspection unchecked
            return (T) t;
        }
        return null;
    }

    /**
     * Get a System Property. If it does not exist or if it's empty, the
     * fallback value will be returned.
     * @param envName       The system property name
     * @param defaultValue  The fallback value
     * @return              The property value or its default value
     */
    protected static String getSystemProperty(String envName, String defaultValue) {
        String property = System.getProperty(envName);
        if (property == null || property.isBlank()) {
            return defaultValue;
        } else {
            return property;
        }
    }

    /**
     * Get a System Property. If it does not exist or if it's empty, the
     * fallback value will be returned.
     * @param envName       The system property name
     * @param defaultValue  The fallback value
     * @return              The property value or its default value
     */
    protected static int getSystemProperty(String envName, int defaultValue) {
        String property = System.getProperty(envName);
        if (property == null || property.isBlank()) {
            return defaultValue;
        } else {
            return Integer.parseInt(property);
        }
    }

    /**
     * Get a System Property. If it does not exist or if it's empty, the
     * fallback value will be returned.
     * @param envName       The system property name
     * @param defaultValue  The fallback value
     * @return              The property value or its default value
     */
    protected static boolean getSystemProperty(String envName, boolean defaultValue) {
        String property = System.getProperty(envName);
        if (property == null || property.isBlank()) {
            return defaultValue;
        } else {
            return Boolean.parseBoolean(property);
        }
    }
}
