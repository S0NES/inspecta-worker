package br.com.vistoria.worker.gateway;

import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.gateway.MockHttpGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/test/http-gateway")
@Profile("!prod")
@RequiredArgsConstructor
public class HttpGatewayMockController {

    private final MockHttpGateway mockHttpGateway;

    public record StubRequest(int status, String body, Map<String, String> headers) {}

    @PostMapping("/stub-next")
    public ResponseEntity<Void> stubNext(@RequestBody StubRequest req) {
        mockHttpGateway.programarProxima(new HttpGatewayResponse(req.status(), req.headers(), req.body()));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        mockHttpGateway.limpar();
        return ResponseEntity.noContent().build();
    }
}
