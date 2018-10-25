#include <jni.h>
#include <string>
#include <opencv2/core.hpp>
#include <opencv2/opencv.hpp>

using namespace cv;
using namespace std;
extern "C" {
    jboolean
    Java_com_shengxuan_featuredetection_MainActivity_isExceedThreshold(JNIEnv *env, jobject instance,
                                                                       jint threshold, jlong inputMat) {
        Mat& mRGB = *(Mat*) inputMat;
        vector<KeyPoint> keypoints;
        Ptr<ORB> detectorORB = ORB::create();
        detectorORB->detect(mRGB, keypoints);
        if (keypoints.size() >= threshold) {
            return true;
        } else {
            return false;
        }
    }
}