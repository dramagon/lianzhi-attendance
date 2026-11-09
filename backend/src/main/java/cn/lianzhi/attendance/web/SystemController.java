package cn.lianzhi.attendance.web;

import cn.lianzhi.attendance.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final boolean demo;
    public SystemController(@Value("${lianzhi.demo:false}") boolean demo) { this.demo = demo; }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        return ApiResponse.ok(Map.of("name", "联智", "version", "0.1.0", "demo", demo));
    }

    @GetMapping("/modules")
    public ApiResponse<List<ModuleInfo>> modules() {
        return ApiResponse.ok(List.of(
                new ModuleInfo(1, "系统基础与登录权限", "登录、会话、角色权限和个人信息", "COMPLETED"),
                new ModuleInfo(2, "公司、员工与办公场所", "基础档案与本机MySQL已验证；地图与设备定位待验证", "IN_PROGRESS"),
                new ModuleInfo(3, "二维码与基础考勤", "动态二维码、定位校验、上下班打卡", "PLANNED"),
                new ModuleInfo(4, "人脸识别考勤", "人脸录入、身份比对与活体检测", "PLANNED"),
                new ModuleInfo(5, "考勤业务与审批", "真实申请、审批审计与考勤回写已接通；生效后撤销待接入", "IN_PROGRESS"),
                new ModuleInfo(6, "数据分析与报表", "出勤统计、工时分析与报表导出", "PLANNED"),
                new ModuleInfo(7, "企业知识库与 AI 助手", "制度问答、考勤查询与业务辅助", "PLANNED"),
                new ModuleInfo(8, "移动适配与多端使用", "完善员工移动端与 uni-app 适配", "PLANNED"),
                new ModuleInfo(9, "部署、集成与交付", "容器部署、集成测试和交付文档", "PLANNED")
        ));
    }
    public record ModuleInfo(int order, String name, String description, String status) {}
}
