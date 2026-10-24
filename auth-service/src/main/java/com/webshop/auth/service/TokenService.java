package com.webshop.auth.service;

import com.webshop.auth.dto.response.TokenResponse;
import com.webshop.auth.entity.User;

public interface TokenService {

    /** Phát hành access token cho user đã xác thực. */
    TokenResponse issue(User user);
}
