package org.librazy.demo.dubbo.web;

import com.alibaba.dubbo.config.annotation.Reference;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.librazy.demo.dubbo.config.JwtConfigParams;
import org.librazy.demo.dubbo.config.SecurityInstanceUtils;
import org.librazy.demo.dubbo.domain.UserEntity;
import org.librazy.demo.dubbo.model.JwtRefreshForm;
import org.librazy.demo.dubbo.model.SrpChallengeForm;
import org.librazy.demo.dubbo.service.JwtTokenService;
import org.librazy.demo.dubbo.service.UserService;
import org.librazy.demo.dubbo.service.UserSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.validation.Valid;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@RestController
public class HomeController {

    private static final String STATUS = "status";

    private static final String OK = "ok";

    private static final String ERROR = "error";

    private final JwtConfigParams jwtConfigParams;

    private final UserService userService;

    @Reference
    private JwtTokenService jwtTokenService;

    @Reference
    private UserSessionService userSessionService;

    private static Logger logger = LoggerFactory.getLogger(HomeController.class);


    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    public HomeController(JwtConfigParams jwtConfigParams, UserService userService, @Autowired(required = false) JwtTokenService jwtTokenService, @Autowired(required = false) UserSessionService userSessionService) {
        this.userService = userService;
        this.jwtTokenService = jwtTokenService;
        this.jwtConfigParams = jwtConfigParams;
        this.userSessionService = userSessionService;
    }

    @GetMapping("204")
    public ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("refresh")
    public ResponseEntity<Map<String, String>> refresh(@Valid @RequestBody JwtRefreshForm form, @RequestHeader("Authorization") String auth, Principal sender) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException, IllegalBlockSizeException {
        String jwt = auth.substring(jwtConfigParams.tokenHead.length() + 1);
        Claims claims = Jwts.claims(jwtTokenService.validateClaimsFromToken(jwt));
        String sid = (String) claims.get("jti");
        String key = userSessionService.getKey(sender.getName(), sid);
        Map<String, String> result = new HashMap<>();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(SecurityInstanceUtils.getSha512().digest(key.getBytes()), 0, 32, "AES"), new GCMParameterSpec(96, form.getNonce().getBytes()));
        String expected = form.getNonce() + " " + form.getTimestamp();
        boolean nonceValid = userSessionService.validNonce(form.getNonce());
        String actual = new String(cipher.doFinal(Base64.getDecoder().decode(form.getSign())));
        boolean timeValid = Math.abs(form.getTimestamp() - jwtTokenService.getClock()) < 10000;
        boolean signValid = expected.equals(actual);
        if (!timeValid || !signValid || !nonceValid) {
            result.put(STATUS, ERROR);
            return ResponseEntity.status(401).body(result);
        }
        String newJwt = jwtTokenService.refreshToken(jwt);
        if (newJwt == null) {
            result.put(STATUS, ERROR);
            return ResponseEntity.status(403).body(result);
        }
        userSessionService.refreshSession(sender.getName(), sid);
        result.put(STATUS, OK);
        result.put("jwt", newJwt);
        return ResponseEntity.ok(result);
    }

    // should never fails so you cannot tells if a email is registered
    @PostMapping("code")
    public ResponseEntity<Map<String, String>> code(@Valid @RequestBody SrpChallengeForm form) {
        Map<String, String> result = new HashMap<>();
        final UserEntity ud = userService.findByEmail(form.getEmail());

        if (ud != null) {
            // WAI: Prevent spoofing whether one email is already registered
            logger.warn("attempt to request code for already registered email: {}", form.getEmail());
            result.put(STATUS, OK);
            return ResponseEntity.ok(result);
        }
        try {
            String code = userSessionService.sendCode(form.getEmail());
            if (code != null) {
                result.put(STATUS, OK);
                result.put("mock", code); // TOD: remove it when email sms api available
                return ResponseEntity.ok(result);
            } else {
                logger.warn("too frequent code request for {}", form.getEmail());
                result.put(STATUS, ERROR);
                result.put("msg", ";too much request");
                return ResponseEntity.status(429).header("Retry-After", "60").body(result);
            }
        } catch (Exception e) {
            logger.warn("exception when requesting code for {}", form.getEmail());
            logger.warn("exception:", e);
            result.put(STATUS, ERROR);
            result.put("msg", ";email number not valid");
            return ResponseEntity.badRequest().body(result);
        }
    }
}
