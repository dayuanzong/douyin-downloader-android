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

    @Test
    fun buildDetailFromRenderedSnapshot_filtersStaticAssetsFromRequestNoise() {
        val realStill =
            "https://p9-pc-sign.douyinpic.com/tos-cn-i-0813c000-ce/owe2kkgDeA5hgk4PDfPjPHAEY4AjuTAfWJAnPI~tplv-dy-aweme-images:q75.webp?biz_tag=aweme_images"
        val motionUrl =
            "https://v26-web.douyinvod.com/example/video/tos/cn/tos-cn-ve-15c000-ce/o4NzEAiEIEw0e28PqPiekbn8sS5wuAA6BCheG4/?mime_type=video_mp4"
        val pageCover =
            "https://p3-pc-sign.douyinpic.com/tos-cn-i-0813c000-ce/oYfk1doIAt7eAIrEqELTAPDGgV8NAJ5ReHyBry~noop.jpeg?biz_tag=pcweb_cover"
        val staticBackground =
            "https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/ies/douyin_web/media/blackBg.b7bedc994a938699.png"
        val logoImage =
            "https://lf-douyin-pc-web.douyinstatic.com/obj/douyin-pc-web/2025_0313_logo.png"
        val galleryNoise =
            "https://p3-pc-sign.douyinpic.com/obj/tos-cn-i-tsj2vxp0zn/427b632213784532a4076c3b0ab269fb?from=876277922"

        val snapshot = RenderedPageSnapshot(
            title = "我们终于长到这般年纪，上可共情长辈的沧桑，下可理解少年的锋芒 - 抖音",
            scripts = emptyList(),
            images = listOf(
                RenderedImageNode(src = realStill, width = 1080, height = 1440, className = "", alt = ""),
                RenderedImageNode(src = pageCover, width = 1080, height = 1440, className = "", alt = ""),
                RenderedImageNode(src = staticBackground, width = 1080, height = 1080, className = "", alt = ""),
            ),
            videos = listOf(motionUrl),
            requests = listOf(realStill, motionUrl, pageCover, staticBackground, logoImage, galleryNoise),
        )

        val detail = DouyinParsing.buildDetailFromRenderedSnapshot(snapshot, "7630290857161395057")

        assertNotNull(detail)
        assertEquals(1, detail?.imageAssets?.size)
        assertEquals(realStill, detail?.imageAssets?.first()?.imageCandidates?.first())
        assertEquals(motionUrl, detail?.imageAssets?.first()?.motionCandidates?.first())
    }

    @Test
    fun candidateWorkUrls_prefersDesktopNotePageForShareNote() {
        val urls = DouyinParsing.candidateWorkUrls(
            "7630290857161395057",
            "https://www.iesdouyin.com/share/note/7630290857161395057/",
        )

        assertEquals(
            "https://www.douyin.com/note/7630290857161395057?previous_page=app_code_link",
            urls.first(),
        )
    }

    @Test
    fun normalizeRenderedAwemeDetail_keepsImageMotionVideo() {
        val detailJson =
            """
            {
              "awemeId": "7630290857161395057",
              "itemTitle": "motion sample",
              "images": [
                {
                  "urlList": ["https://p9-pc-sign.douyinpic.com/tos-cn-i-0813c000-ce/still.webp"],
                  "video": {
                    "playAddr": [
                      {
                        "src": "https://v26-web.douyinvod.com/video/tos/example/?mime_type=video_mp4"
                      }
                    ],
                    "bitRateList": [
                      {
                        "bitRate": 1062544,
                        "width": 1280,
                        "height": 720,
                        "playAddr": [
                          {
                            "src": "https://v26-web.douyinvod.com/video/tos/example/?mime_type=video_mp4"
                          }
                        ]
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()

        val detail = DouyinParsing.normalizeAwemeDetail(org.json.JSONObject(detailJson))

        assertEquals(1, detail.imageAssets.size)
        assertEquals(
            "https://v26-web.douyinvod.com/video/tos/example/?mime_type=video_mp4",
            detail.imageAssets.first().motionCandidates.first(),
        )
    }

    @Test
    fun looksLikeRealVideoUrl_rejectsAudioWrappedAsPlayUrl() {
        val isVideo = DouyinParsing.looksLikeRealVideoUrl(
            "https://aweme.snssdk.com/aweme/v1/play/?video_id=https://sf6-cdn-tos.douyinstatic.com/obj/ies-music/sample.mp3",
        )

        assertEquals(false, isVideo)
    }
}
