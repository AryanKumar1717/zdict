package dev.zdict.spring;

import dev.zdict.core.ZdictConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuration properties for the zdict Spring Boot starter.
 */
@ConfigurationProperties(prefix = "zdict")
public class ZdictProperties extends ZdictConfig {

    private boolean enabled = false;
    private String dictionaryPath = "";
    private String seedDictionaryPath = "";
    private List<String> paths = List.of();
    private boolean allowAuthenticated = false;
    private boolean skipReflectedInput = true;

    private Set<String> denylistedFields = new HashSet<>(Set.of(
            "password", "token", "accesstoken", "refreshtoken", "secret",
            "email", "phone", "mobile", "address", "ssn", "aadhaar",
            "pan", "card", "cvv", "authorization"
    ));

    private Dashboard dashboard = new Dashboard();

    public static class Dashboard {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSeedDictionaryPath() {
        if (seedDictionaryPath != null && !seedDictionaryPath.isBlank()) {
            return seedDictionaryPath;
        }
        return dictionaryPath;
    }

    public void setSeedDictionaryPath(String seedDictionaryPath) {
        this.seedDictionaryPath = seedDictionaryPath;
    }

    public String getDictionaryPath() {
        return getSeedDictionaryPath();
    }

    public void setDictionaryPath(String dictionaryPath) {
        this.dictionaryPath = dictionaryPath;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    public boolean isAllowAuthenticated() {
        return allowAuthenticated;
    }

    public void setAllowAuthenticated(boolean allowAuthenticated) {
        this.allowAuthenticated = allowAuthenticated;
    }

    public boolean isSkipReflectedInput() {
        return skipReflectedInput;
    }

    public void setSkipReflectedInput(boolean skipReflectedInput) {
        this.skipReflectedInput = skipReflectedInput;
    }

    public Set<String> getDenylistedFields() {
        return denylistedFields;
    }

    public void setDenylistedFields(Set<String> denylistedFields) {
        this.denylistedFields = denylistedFields;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public void setDashboard(Dashboard dashboard) {
        this.dashboard = dashboard;
    }

    public boolean isDashboardEnabled() {
        return dashboard != null && dashboard.isEnabled();
    }
}
