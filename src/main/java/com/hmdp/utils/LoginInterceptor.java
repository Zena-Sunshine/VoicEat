package com.hmdp.utils;


import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {



    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

        UserHolder.removeUser();
    }
}




/*
@Component
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");
        if (StrUtil.isBlank(authHeader) || !authHeader.startsWith("Bearer ")) {
            // 无 token，直接放行（后续通过权限注解控制）
            return true;
        }
        String token = authHeader.substring(7);
        try {
            Claims claims = jwtUtils.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            String nickName = claims.get("nickName", String.class);
            String icon = claims.get("icon", String.class);

            UserDTO userDTO = new UserDTO();
            userDTO.setId(userId);
            userDTO.setNickName(nickName);
            userDTO.setIcon(icon);
            UserHolder.saveUser(userDTO);
            return true;
        } catch (ExpiredJwtException e) {
            // token 过期，返回 401，前端收到后应调用刷新接口
            writeErrorResponse(response, "access token expired");
            return false;
        } catch (JwtException e) {
            // 无效 token
            writeErrorResponse(response, "invalid token");
            return false;
        }
    }

    private void writeErrorResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"errorMsg\":\"" + message + "\"}");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
*/
