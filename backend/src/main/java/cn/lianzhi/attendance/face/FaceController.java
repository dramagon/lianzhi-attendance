package cn.lianzhi.attendance.face;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.AccountService;
import cn.lianzhi.attendance.attendance.AttendanceProblem;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/face")
public class FaceController {
    private final AccountService accounts;
    public FaceController(AccountService accounts) { this.accounts = accounts; }
    @GetMapping("/status")
    public ApiResponse<FaceProvider.Capabilities> status(Principal p) {
        accounts.current(p.getName());
        return ApiResponse.ok(new FaceProvider.Capabilities(false, false, false, "NONE", "虹软 SDK、授权及服务端身份验证尚未接入；不会采集或上传人脸数据"));
    }
    @PostMapping({"/enrollment", "/verification"})
    public ApiResponse<Void> unavailable(Principal p) {
        accounts.current(p.getName());
        throw AttendanceProblem.unavailable("人脸 SDK 尚未接入，无法登记或验证；未产生任何人脸凭据");
    }
}
