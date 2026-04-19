package com.douyindownloader.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DouyinParsingTest {
    @Test
    fun buildDetailFromRenderedSnapshot_keepsLivePhotoAndFollowingStaticImage() {
        val genericCover =
            "https://p26-sign.douyinpic.com/obj/tos-cn-p-0015/o8jANIDnFq2w2tbQ59rfABOAZkAEIDAfgp7oRE?from=327834062"
        val livePhotoStill =
            "https://p26-sign.douyinpic.com/tos-cn-i-0813c001/oceF3prABAEIAjAHafGeGTQutIAbiZ7U2LUBGV~tplv-dy-water-v2.webp?biz_tag=aweme_images"
        val livePhotoStillNoWatermark =
            "https://p26-sign.douyinpic.com/tos-cn-i-0813c001/oceF3prABAEIAjAHafGeGTQutIAbiZ7U2LUBGV.webp?biz_tag=aweme_images"
        val realStaticImage =
            "https://p3-sign.douyinpic.com/tos-cn-i-0813c001/oYrb1fA9AAaQF6QeVd3gEBiIuC0g2Y2BfAnfM~tplv-dy-water-v2.jpeg?biz_tag=aweme_images"
        val realStaticImageNoWatermark =
            "https://p3-sign.douyinpic.com/tos-cn-i-0813c001/oYrb1fA9AAaQF6QeVd3gEBiIuC0g2Y2BfAnfM.jpeg?biz_tag=aweme_images"
        val motionUrl =
            "https://v5-default.365yg.com/example/video/tos/cn/tos-cn-ve-15/oM2b97frpIDCApAktB2FnvNEEADCZ6AOwgfMIo/?mime_type=video_mp4"

        val snapshot = RenderedPageSnapshot(
            title = "鎴戜滑鍥㈢粨涓€蹇冿紝鐢ㄦ垜浠鑲変慨鎴愭柊鐨勯暱鍩?- 鎶栭煶",
            scripts = emptyList(),
            images = listOf(
                RenderedImageNode(src = genericCover, width = 1080, height = 1440, className = "", alt = ""),
                RenderedImageNode(src = livePhotoStill, width = 1080, height = 1440, className = "", alt = ""),
            ),
            videos = listOf(motionUrl),
            requests = listOf(genericCover, livePhotoStill, realStaticImage, motionUrl),
        )

        val detail = DouyinParsing.buildDetailFromRenderedSnapshot(snapshot, "7627888901880992422")

        assertNotNull(detail)
        assertEquals(2, detail?.imageAssets?.size)
        assertEquals(livePhotoStillNoWatermark, detail?.imageAssets?.first()?.imageCandidates?.first())
        assertEquals(livePhotoStill, detail?.imageAssets?.first()?.imageCandidates?.get(1))
        assertEquals(motionUrl, detail?.imageAssets?.first()?.motionCandidates?.first())
        assertEquals(realStaticImageNoWatermark, detail?.imageAssets?.get(1)?.imageCandidates?.first())
        assertEquals(realStaticImage, detail?.imageAssets?.get(1)?.imageCandidates?.get(1))
    }

    @Test
    fun extractAwemeDetailFromSlidesInfo_prefersImagesFromApiResponse() {
        val slidesInfoJson =
            """
            {
              "aweme_details": [
                {
                  "aweme_id": "7627888901880992422",
                  "desc": "我们团结一心，用我们血肉修成新的长城",
                  "images": [
                    {
                      "clip_type": 4,
                      "download_url_list": [
                        "https://p26-sign.douyinpic.com/tos-cn-p-0015/o8jANIDnFq2w2tbQ59rfABOAZkAEIDAfgp7oRE~tplv-dy-water-v2.webp?biz_tag=aweme_images"
                      ],
                      "video": {
                        "play_addr_h264": {
                          "url_list": [
                            "https://v5-default.365yg.com/example/video/tos/cn/tos-cn-ve-15/oM2b97frpIDCApAktB2FnvNEEADCZ6AOwgfMIo/?mime_type=video_mp4"
                          ]
                        }
                      }
                    },
                    {
                      "clip_type": 2,
                      "download_url_list": [
                        "https://p3-sign.douyinpic.com/tos-cn-i-0813c001/oceF3prABAEIAjAHafGeGTQutIAbiZ7U2LUBGV~tplv-dy-water-v2.webp?biz_tag=aweme_images"
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val rawDetail = DouyinParsing.extractAwemeDetailFromSlidesInfo(slidesInfoJson, "7627888901880992422")
        val detail = rawDetail?.let(DouyinParsing::normalizeAwemeDetail)

        assertNotNull(detail)
        assertEquals(2, detail?.imageAssets?.size)
        assertEquals(
            "https://p26-sign.douyinpic.com/tos-cn-p-0015/o8jANIDnFq2w2tbQ59rfABOAZkAEIDAfgp7oRE.webp?biz_tag=aweme_images",
            detail?.imageAssets?.get(0)?.imageCandidates?.first(),
        )
        assertEquals(
            "https://v5-default.365yg.com/example/video/tos/cn/tos-cn-ve-15/oM2b97frpIDCApAktB2FnvNEEADCZ6AOwgfMIo/?mime_type=video_mp4",
            detail?.imageAssets?.get(0)?.motionCandidates?.first(),
        )
        assertEquals(
            "https://p3-sign.douyinpic.com/tos-cn-i-0813c001/oceF3prABAEIAjAHafGeGTQutIAbiZ7U2LUBGV.webp?biz_tag=aweme_images",
            detail?.imageAssets?.get(1)?.imageCandidates?.first(),
        )
    }
}
