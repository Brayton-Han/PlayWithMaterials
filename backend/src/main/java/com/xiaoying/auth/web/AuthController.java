package com.xiaoying.auth.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.auth.security.CurrentUser;
import com.xiaoying.auth.security.SessionCookies;
import com.xiaoying.auth.service.AuthService;
import com.xiaoying.auth.service.AuthService.Authenticated;

/** {@code /api/auth/*}（api.yaml tag auth，F-12）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionCookies cookies;

    public AuthController(AuthService authService, SessionCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest request,
                                            @CookieValue(name = SessionCookies.NAME, required = false) String sid) {
        Authenticated result = authService.register(request.username(), request.email(), request.password(), sid);
        return withSession(HttpStatus.CREATED, result);
    }

    @PostMapping("/login")
    public ResponseEntity<UserDto> login(@Valid @RequestBody LoginRequest request,
                                         @CookieValue(name = SessionCookies.NAME, required = false) String sid) {
        Authenticated result = authService.login(request.username(), request.password(), sid);
        return withSession(HttpStatus.OK, result);
    }

    /** 幂等：没有会话时也返回 204 并清除 Cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = SessionCookies.NAME, required = false) String sid) {
        authService.logout(sid);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    @GetMapping("/me")
    public UserDto me(CurrentUser currentUser) {
        return UserDto.from(authService.currentUser(currentUser.id()));
    }

    private ResponseEntity<UserDto> withSession(HttpStatus status, Authenticated result) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.issue(result.token()))
                .body(UserDto.from(result.user()));
    }
}
