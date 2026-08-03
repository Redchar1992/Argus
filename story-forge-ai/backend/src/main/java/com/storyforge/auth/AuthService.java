package com.storyforge.auth;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.analytics.ProductAnalyticsService;
import com.storyforge.analytics.ProductEventNames;
import com.storyforge.common.exception.ApiException;
import com.storyforge.common.privacy.PrivacyPolicy;
import com.storyforge.common.security.JwtService;
import com.storyforge.cost.AiCreditService;
import com.storyforge.user.SysUser;
import com.storyforge.user.SysUserMapper;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final ZoneId PILOT_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AiCreditService credits;
    private final ProductAnalyticsService analytics;

    public AuthService(
            SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AiCreditService credits,
            ProductAnalyticsService analytics
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.credits = credits;
        this.analytics = analytics;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        Long count = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)
        );
        if (count > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setVipLevel("FREE");
        user.setPrivacyVersion(PrivacyPolicy.CURRENT_VERSION);
        user.setPrivacyAcceptedTime(LocalDateTime.now());
        user.setCreatedTime(LocalDateTime.now());
        userMapper.insert(user);
        credits.ensureWallet(user.getId());
        analytics.record(
                ProductEventNames.USER_REGISTERED,
                user.getId(),
                null,
                null,
                "user:" + user.getId() + ":registered",
                java.util.Map.of("privacyVersion", PrivacyPolicy.CURRENT_VERSION)
        );
        return new AuthResponse(jwtService.createToken(user), user.getId());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String username = request.username().trim();
        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)
        );
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        if (!PrivacyPolicy.CURRENT_VERSION.equals(user.getPrivacyVersion())) {
            if (!Boolean.TRUE.equals(request.privacyAccepted())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "PRIVACY_CONSENT_REQUIRED",
                        "请先阅读并同意当前隐私说明"
                );
            }
            user.setPrivacyVersion(PrivacyPolicy.CURRENT_VERSION);
            user.setPrivacyAcceptedTime(LocalDateTime.now());
            userMapper.updateById(user);
        }
        LocalDate today = LocalDate.now(PILOT_TIME_ZONE);
        analytics.record(
                ProductEventNames.USER_LOGIN_DAILY,
                user.getId(),
                null,
                null,
                "user:" + user.getId() + ":login:" + today
        );
        return new AuthResponse(jwtService.createToken(user), user.getId());
    }
}
