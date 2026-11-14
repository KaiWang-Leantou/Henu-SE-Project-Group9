package com.henu.whiteboard.auth;

import com.henu.whiteboard.common.ApiCode;
import com.henu.whiteboard.common.ApiException;

/**
 * 请求级用户上下文；AuthFilter 在 finally 中清理，避免线程池串号。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new ApiException(ApiCode.AUTH_REQUIRED, "请先登录");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
