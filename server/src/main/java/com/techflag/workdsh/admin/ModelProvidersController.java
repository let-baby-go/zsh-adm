package com.techflag.workdsh.admin;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
@RestController
public class ModelProvidersController {
 private final ModelProviders providers;private final ModelGatewayKeys keys;private final AuthService auth;
 public ModelProvidersController(ModelProviders providers,ModelGatewayKeys keys,AuthService auth){this.providers=providers;this.keys=keys;this.auth=auth;}
 @GetMapping("/api/admin/model-providers") public ResponseEntity<ModelProviders.View> read(@RequestHeader("Authorization")String bearer){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(providers.read(auth.actor(bearer)));}
 @PostMapping("/api/admin/model-providers/{id}/reveal-key") public ResponseEntity<java.util.Map<String,String>> reveal(@RequestHeader("Authorization")String bearer,@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("key",providers.reveal(auth.actor(bearer),id)));}
 /** Providers and the internal access key are saved in one transaction with one authentication. */
 @PutMapping("/api/admin/model-providers") @Transactional public ResponseEntity<ModelProviders.View> update(@RequestHeader("Authorization")String bearer,@RequestBody ModelProviders.Update update){var actor=auth.actor(bearer);var view=providers.update(actor,update);keys.update(actor,update.accessKey());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);}
}
