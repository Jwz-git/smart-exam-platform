package com.smartexam.user;

import com.smartexam.common.DomainException;
import com.smartexam.common.PageResult;
import com.smartexam.user.UserModels.CreateUserRequest;
import com.smartexam.user.UserModels.UserView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员的用户维护规则。
 *
 * <p>范围有意做窄：只有「查、增、启用/停用」三件事，没有改密码、改角色和删除。原因是
 * {@code plan.md} 第 2.1 节明确不建设复杂后台，而这三项已经足够支撑「管理员建号 →
 * 教师出题 → 学生答题」的完整演示。
 *
 * <p>两条不可放松的规则：
 * <ol>
 *   <li>用户永不物理删除。存在答卷的账号一旦删除，历史成绩就失去归属，只允许停用；</li>
 *   <li>管理员不能停用自己。否则一个单管理员系统会被一次误操作彻底锁死，
 *       再也没有账号能重新启用任何人。</li>
 * </ol>
 */
@Service
public class UserService {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository; this.passwordEncoder = passwordEncoder;
    }

    /** 分页查询用户，支持关键词、角色和状态筛选。参数范围由 Controller 校验。 */
    public PageResult<UserView> list(String keyword, AppUser.Role role, AppUser.Status status, int page, int size) {
        return repository.findPage(keyword, role, status, page, size);
    }

    /**
     * 新增用户。
     *
     * <p>先查一次重名给出可读错误，再靠唯一约束兜住并发插入：两个管理员同时提交同一个用户名时，
     * 先查后插之间存在竞态，只有数据库约束能真正保证唯一。
     *
     * <p>密码在这里就地哈希，明文不落库、不进日志、不出现在任何响应里。
     */
    @Transactional
    public UserView create(CreateUserRequest request) {
        if (repository.findByUsername(request.username().trim()).isPresent()) {
            throw new DomainException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        try {
            long id = repository.create(request, passwordEncoder.encode(request.password()));
            return repository.findViewById(id).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw new DomainException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
    }

    /**
     * 启用或停用用户。
     *
     * <p>{@code operatorId} 是当前登录的管理员，用于拦住「停用自己」。
     * 停用后该账号既无法登录，也无法继续使用此前签发的 JWT——
     * 拦截点在 {@code SecurityConfig#jwtAuthenticationConverter}，它每次请求都回查一次用户状态。
     */
    @Transactional
    public UserView updateStatus(long id, AppUser.Status status, long operatorId) {
        UserView user = repository.findViewById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (id == operatorId && status == AppUser.Status.DISABLED) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "CANNOT_DISABLE_SELF", "不能停用当前登录的管理员账号");
        }
        if (user.status() == status) return user;
        repository.updateStatus(id, status);
        return repository.findViewById(id).orElseThrow();
    }
}
