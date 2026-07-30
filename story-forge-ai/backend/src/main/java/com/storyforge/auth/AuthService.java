package com.storyforge.auth;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.exception.ApiException;
import com.storyforge.common.security.JwtService;
import com.storyforge.user.SysUser;
import com.storyforge.user.SysUserMapper;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
        user.setCreatedTime(LocalDateTime.now());
        userMapper.insert(user);
        return new AuthResponse(jwtService.createToken(user), user.getId());
    }

    public AuthResponse login(LoginRequest request) {
        String username = request.username().trim();
        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)
        );
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        return new AuthResponse(jwtService.createToken(user), user.getId());
    }
}
