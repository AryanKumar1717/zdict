package dev.zdict.demo;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Custom Actuator endpoint at /actuator/zdict for reading state and toggling kill switch.
 */
@Component
@Endpoint(id = "zdict")
public class ZdictEndpoint {

    private final ZstdDictFilter filter;
    private final ZdictCircuitBreaker circuitBreaker;

    public ZdictEndpoint(ZstdDictFilter filter, ZdictCircuitBreaker circuitBreaker) {
        this.filter = filter;
        this.circuitBreaker = circuitBreaker;
    }

    @ReadOperation
    public Map<String, Object> state() {
        return Map.of(
                "enabled", filter.isEnabled(),
                "circuitOpen", circuitBreaker.isOpen()
        );
    }

    @WriteOperation
    public Map<String, Object> setEnabled(boolean enabled) {
        filter.setEnabled(enabled);
        return Map.of(
                "enabled", filter.isEnabled(),
                "circuitOpen", circuitBreaker.isOpen()
        );
    }
}
