package dev.zdict.demo;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the raw dictionary bytes so clients can download them.
 * GET /zdict/dictionary/{id} -> dictionary bytes or 404.
 */
@RestController
@RequestMapping("/zdict/dictionary")
public class DictionaryController {

    private final ZstdDictFilter filter;

    public DictionaryController(ZstdDictFilter filter) {
        this.filter = filter;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> getDictionary(@PathVariable String id) {
        if (filter.getDictId() == null || !filter.getDictId().equals(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(filter.getDictBytes().length);
        return new ResponseEntity<>(filter.getDictBytes(), headers, HttpStatus.OK);
    }
}
