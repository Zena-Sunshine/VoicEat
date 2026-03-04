package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.jwt.Claims;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JwtUtils;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 孙伟成
 * @since 2026-2-4
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /*@Resource
    private IUserService userService;
    @Resource
    private JwtUtils jwtUtils;*/

    @Override
    public Result sendCode(String phone, HttpSession session) {

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        String code = RandomUtil.randomNumbers(6);

        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("发送短信验证码成功,验证码：{}",code);

        return Result.ok();
    }

    /*@PostMapping("/refresh")
    public Result refreshToken(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        if (StringUtils.isBlank(refreshToken)) {
            return Result.fail("refresh token 不能为空");
        }
        try {
            Claims claims = jwtUtils.parseToken(refreshToken);
            Long userId = claims.get("userId", Long.class);
            // 可选：检查用户是否存在
            User user = userService.getById(userId);
            if (user == null) {
                return Result.fail("用户不存在");
            }

            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            String newAccessToken = jwtUtils.createAccessToken(userDTO);
            // 滑动过期：重新生成 refresh token
            String newRefreshToken = jwtUtils.createRefreshToken(userId);

            Map<String, Object> tokenMap = new HashMap<>();
            tokenMap.put("accessToken", newAccessToken);
            tokenMap.put("refreshToken", newRefreshToken);
            tokenMap.put("expiresIn", jwtProperties.getAccessTokenExpire());
            return Result.ok(tokenMap);
        } catch (ExpiredJwtException e) {
            return Result.fail("refresh token 已过期，请重新登录");
        } catch (JwtException e) {
            return Result.fail("无效的 refresh token");
        }
    }*/

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
//        // 3.校验验证码
//        Object cacheCode = session.getAttribute("code");
        // 3.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();

        // @TODO 先不校验验证码
/*        if(cacheCode == null || !cacheCode.equals(code)){
            //3.不一致，报错
            return Result.fail("验证码错误");
        }*/
        //注释掉以上部分


        //一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if(user == null){
            //不存在，则创建
            user =  createUserWithPhone(phone);
        }

        // 7.保存用户信息到 redis中
        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), //beanToMap方法执行了对象到Map的转换
                CopyOptions.create()
                        .setIgnoreNullValue(true) //BeanUtil在转换过程中忽略所有null值的属性
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); //对于每个字段值，它简单地调用toString()方法，将字段值转换为字符串。
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8.返回token
        return Result.ok(token);

    }


   /*@PostMapping("/login")
   public Result login(@RequestBody LoginFormDTO loginForm) {
       String phone = loginForm.getPhone();
       if (RegexUtils.isPhoneInvalid(phone)) {
           return Result.fail("手机号格式错误");
       }

       // 校验验证码
       String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
       String code = loginForm.getCode();
       if (cacheCode == null || !cacheCode.equals(code)) {
           return Result.fail("验证码错误");
       }

       // 查询或创建用户
       User user = userService.lambdaQuery().eq(User::getPhone, phone).one();
       if (user == null) {
           user = userService.createUserWithPhone(phone);
       }

       // 转换为 UserDTO
       UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

       // 生成双 token
       String accessToken = jwtUtils.createAccessToken(userDTO);
       String refreshToken = jwtUtils.createRefreshToken(userDTO.getId());

       // 返回给前端
       Map<String, Object> tokenMap = new HashMap<>();
       tokenMap.put("accessToken", accessToken);
       tokenMap.put("refreshToken", refreshToken);
       tokenMap.put("expiresIn", jwtProperties.getAccessTokenExpire()); // 若需要

       return Result.ok(tokenMap);
   }*/

    /*@Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        User user = query().eq("phone", phone).one();

        if (user == null) {
            user = createUserWithPhone(phone);
        }

        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)-> fieldValue.toString()));
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }
*/
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            if ((num & 1) == 0) {
                break;
            }else {
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
