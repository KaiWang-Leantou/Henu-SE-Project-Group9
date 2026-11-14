package com.henu.whiteboard.auth;

/**
 * JWT 解析结果；仅包含鉴权所需的最小声明，用户最新状态仍以数据库为准。
 */
public record JwtPayload(String userId, String name, boolean guest) {
}
