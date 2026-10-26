package cn.lianzhi.attendance.web;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.*;
import cn.lianzhi.attendance.employee.EmployeeService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/profile")
public class AvatarController {
    private final AccountService accounts;
    private final EmployeeService employees;
    private final AvatarStorage avatars;
    public AvatarController(AccountService accounts,EmployeeService employees,AvatarStorage avatars) { this.accounts=accounts; this.employees=employees; this.avatars=avatars; }
    @PostMapping(value="/avatar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserView> upload(Authentication actor,@RequestParam("file") MultipartFile file) {
        avatars.save(accounts.current(actor.getName()).id(),file);
        return ApiResponse.ok(accounts.current(actor.getName()));
    }
    @DeleteMapping("/avatar")
    public ApiResponse<UserView> remove(Authentication actor) {
        avatars.remove(accounts.current(actor.getName()).id());
        return ApiResponse.ok(accounts.current(actor.getName()));
    }
    @GetMapping("/avatars/{userId}")
    public ResponseEntity<byte[]> read(Authentication actor,@PathVariable Long userId) {
        employees.get(actor,userId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(avatars.read(userId));
    }
}
