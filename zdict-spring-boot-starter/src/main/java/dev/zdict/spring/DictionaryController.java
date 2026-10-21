package dev.zdict.spring;

import dev.zdict.core.DictionaryRegistry;
import dev.zdict.core.DictionaryVersion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves raw dictionary bytes by ID for any stored version (ACTIVE or RETIRED).
 * GET /zdict/dict/{id} or /zdict/dictionary/{id} -> dictionary bytes or 404.
 */
@RestController
@RequestMapping("/zdict")
public class DictionaryController {

    private final DictionaryRegistry registry;

    public DictionaryController(DictionaryRegistry registry) {
        this.registry = registry;
    }

    @GetMapping({"/dict/{id}", "/dictionary/{id}", "/dict/{group}/{id}"})
    public ResponseEntity<byte[]> getDictionary(@PathVariable(required = false) String group, @PathVariable String id) {
        DictionaryVersion v = registry.getById(id);
        if (v == null || v.getDictBytes() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        byte[] bytes = v.getDictBytes();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }
}
