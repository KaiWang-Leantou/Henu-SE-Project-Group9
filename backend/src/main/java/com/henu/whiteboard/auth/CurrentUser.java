package com.henu.whiteboard.auth;

/**
 * 当前请求用户快照；由 AuthFilter 从数据库读取后放入 ThreadLocal，避免信任过期 JWT 声明。
 */
public record CurrentUser(String id, String username, String nickname, boolean guest) {
}
