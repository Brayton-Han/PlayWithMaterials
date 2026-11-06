package com.xiaoying.account.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xiaoying.account.service.AccountService;
import com.xiaoying.auth.security.CurrentUser;
import com.xiaoying.auth.security.SessionCookies;

/** {@code /api/account*}（api.yaml tag account，F-28）。没有路径参数，只能操作当前登录用户。 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService accountService;
    private final SessionCookies cookies;

    public AccountController(AccountService accountService, SessionCookies cookies) {
        this.accountService = accountService;
        this.cookies = cookies;
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(CurrentUser currentUser,
                                               @CookieValue(name = SessionCookies.NAME, required = false) String sid,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(currentUser.id(), sid, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(CurrentUser currentUser,
                                              @Valid @RequestBody DeleteAccountRequest request) {
        accountService.deleteAccount(currentUser.id(), request.confirmText());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }
}
