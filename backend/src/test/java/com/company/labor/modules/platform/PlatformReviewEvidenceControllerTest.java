package com.company.labor.modules.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证审核材料预览 HTTP 响应不会把原件作为可缓存下载返回。 */
@ExtendWith(MockitoExtension.class)
class PlatformReviewEvidenceControllerTest {
    /** 委托执行当前任务与材料实时授权检查的领域服务模拟。 */
    @Mock
    private PlatformReviewEvidenceService evidence;
    /** 被测材料预览协议适配器。 */
    @InjectMocks
    private PlatformReviewEvidenceController controller;

    /** 返回 PNG 栅格，并固定无缓存、禁嗅探和 sandbox 响应头。 */
    @Test
    void previewUsesNoStorePngAndRestrictiveHeaders() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        when(evidence.preview(61L, 72L, 0)).thenReturn(png);

        ResponseEntity<byte[]> response = controller.preview(61L, 72L, 0);

        assertArrayEquals(png, response.getBody());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        verify(evidence).preview(61L, 72L, 0);
    }
}
