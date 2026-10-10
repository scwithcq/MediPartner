package com.dsc.medipartner.module.file.controller;

import com.dsc.medipartner.common.result.R;
import com.dsc.medipartner.module.file.domain.vo.FileVO;
import com.dsc.medipartner.module.file.service.FileUploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 统一文件上传（契约 §8.1）：登录即可，orderNo 仅审计用途（阶段 1.5 落 sys_file 后生效）。
 */
@RestController
@RequestMapping("/api/common")
public class FileController {

    private final FileUploadService fileUploadService;

    public FileController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @PostMapping("/upload")
    public R<FileVO> upload(@RequestParam("file") MultipartFile file,
                            @RequestParam("bizType") String bizType,
                            @RequestParam(value = "orderNo", required = false) String orderNo,
                            @RequestParam(value = "watermarkTime", required = false) String watermarkTime) {
        return R.ok(fileUploadService.upload(file, bizType, watermarkTime));
    }
}
