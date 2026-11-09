package cn.lianzhi.attendance.face;

import cn.lianzhi.attendance.common.ApiResponse;
import cn.lianzhi.attendance.identity.AccountService;
import cn.lianzhi.attendance.attendance.AttendanceProblem;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;

@RestController
@RequestMapping("/api/face")
public class FaceController {
    private final AccountService accounts;
    private final FaceService faces;
    public FaceController(AccountService accounts, FaceService faces) { this.accounts = accounts; this.faces=faces; }
    @GetMapping("/status")
    public ApiResponse<FaceService.Status> status(Principal p) {
        return ApiResponse.ok(faces.status(accounts.current(p.getName()).id()));
    }
    @PostMapping("/enrollment")
    public ApiResponse<FaceService.Enrollment> enroll(Principal p, @RequestParam(required=false) MultipartFile image,
        @RequestParam(defaultValue="false") boolean consent) {
        return save(p,image,0,false,consent);
    }
    @PutMapping("/enrollment")
    public ApiResponse<FaceService.Enrollment> update(Principal p, @RequestParam(required=false) MultipartFile image,
        @RequestParam long expectedRevision, @RequestParam(defaultValue="false") boolean consent) {
        return save(p,image,expectedRevision,true,consent);
    }
    private ApiResponse<FaceService.Enrollment> save(Principal p,MultipartFile image,long revision,boolean update,boolean consent) {
        long user=accounts.current(p.getName()).id(); byte[] bytes=bytes(image);
        try { return ApiResponse.ok(faces.enroll(user,bytes,revision,update,consent)); }
        finally { if(bytes!=null) java.util.Arrays.fill(bytes,(byte)0); }
    }
    @DeleteMapping("/enrollment")
    public ApiResponse<FaceService.Enrollment> delete(Principal p,@RequestParam long expectedRevision) {
        return ApiResponse.ok(faces.delete(accounts.current(p.getName()).id(),expectedRevision));
    }
    @PostMapping("/challenges")
    public ApiResponse<FaceService.Challenge> challenge(Principal p,@RequestBody FaceService.ChallengeInput input) {
        return ApiResponse.ok(faces.challenge(accounts.current(p.getName()).id(),input));
    }
    @PostMapping("/verification")
    public ApiResponse<FaceService.Verification> verify(Principal p,@RequestParam(required=false) String challengeId,
        @RequestParam(required=false) MultipartFile image,@RequestParam(defaultValue="false") boolean consent) {
        long user=accounts.current(p.getName()).id(); byte[] bytes=bytes(image);
        try { return ApiResponse.ok(faces.verify(user,challengeId,bytes,consent)); }
        finally { if(bytes!=null) java.util.Arrays.fill(bytes,(byte)0); }
    }
    private byte[] bytes(MultipartFile image) {
        if(image==null) return null;
        if(image.getSize()>2*1024*1024) throw new IllegalArgumentException("采集图像不能超过2MB");
        try { return image.getBytes(); }
        catch(java.io.IOException e) { throw AttendanceProblem.unavailable("采集图像读取失败，请重试"); }
    }
}
