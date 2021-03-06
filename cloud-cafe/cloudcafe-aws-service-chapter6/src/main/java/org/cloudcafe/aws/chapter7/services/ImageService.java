package org.cloudcafe.aws.chapter6.services;

import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.DetectLabelsRequest;
import com.amazonaws.services.rekognition.model.DetectLabelsResult;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.util.IOUtils;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.spring.aop.XRayEnabled;
import lombok.extern.slf4j.Slf4j;
import org.cloudcafe.aws.chapter6.model.Image;
import org.cloudcafe.aws.chapter6.repository.ImageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.transaction.Transactional;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
@Slf4j
@XRayEnabled
public class ImageService {

    public static final String S3_FILE_PATH = "%s/%s";
    private static String UPLOAD_ROOT = "UPLOAD_DIR";

    private AmazonS3Client amazonS3Client;

    private ImageRepository imageRepository;

    AmazonRekognition rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();


    @Value("${bucketName}")
    private String bucketName;

    public ImageService(AmazonS3Client amazonS3Client, ImageRepository imageRepository) {
        this.amazonS3Client = amazonS3Client;
        this.imageRepository = imageRepository;
    }


    public List<Image> findByUsername(String username) {
        Entity mySegment = AWSXRay.beginSegment("findByUsernameService");
        AWSXRay.getGlobalRecorder().setTraceEntity(mySegment);
        List<Image> images= imageRepository.findByUsername(username);
        AWSXRay.endSegment();
        return images;
    }

    private String getImageLabels(File photo) throws IOException {

        ByteBuffer imageBytes;
        try (InputStream inputStream = new FileInputStream(photo)) {
            imageBytes = ByteBuffer.wrap(IOUtils.toByteArray(inputStream));
        }

        DetectLabelsRequest labelsRequest = new DetectLabelsRequest();
        com.amazonaws.services.rekognition.model.Image image = new com.amazonaws.services.rekognition.model.Image();
        image.withBytes(imageBytes);
        labelsRequest.setImage(image);
        DetectLabelsResult detectLabels = rekognitionClient.detectLabels(labelsRequest);
        return detectLabels.getLabels().subList(0, 5).toString();

    }

    public S3ObjectInputStream findOneImage(String fileName, String username) {
        Entity mySegment = AWSXRay.beginSegment("findOneImageService");
        AWSXRay.getGlobalRecorder().setTraceEntity(mySegment);
        S3Object s3Object = amazonS3Client.getObject(bucketName, getFileName(username, fileName));
        AWSXRay.endSegment();
        return s3Object.getObjectContent();
    }

    @Transactional
    public void createImage(MultipartFile file, String username) {


        Entity mySegment = AWSXRay.beginSegment("createImageService");
        AWSXRay.getGlobalRecorder().setTraceEntity(mySegment);

        Paths.get(UPLOAD_ROOT, file.getName());

        try {
            Path copyLocation = Paths.get(UPLOAD_ROOT + File.separator + StringUtils.cleanPath(file.getOriginalFilename()));
            Files.copy(file.getInputStream(), copyLocation, StandardCopyOption.REPLACE_EXISTING);
            amazonS3Client.putObject(bucketName, getFileName(username, file.getOriginalFilename()), copyLocation.toFile());

            String labels = getImageLabels(copyLocation.toFile());
            imageRepository.save(Image.builder().labels(labels)
                    .bucketName(bucketName)
                    .username(username)
                    .name(file.getOriginalFilename()).build());
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Could not store file " + file.getOriginalFilename()
                    + ". Please try again!");
        }
        AWSXRay.endSegment();

    }

    @Transactional
    public void deleteImage(String fileName, String username) {
        Entity mySegment = AWSXRay.beginSegment("deleteImageService");
        AWSXRay.getGlobalRecorder().setTraceEntity(mySegment);

        amazonS3Client.deleteObject(bucketName, getFileName(username, fileName));
        imageRepository.deleteImageByNameAndUsername(fileName, username);
        AWSXRay.endSegment();
    }

    private String getFileName(String username, String fileName) {
        return String.format(S3_FILE_PATH, username, fileName);
    }

    @Bean
    private CommandLineRunner init() {
        return (args) -> {
            FileSystemUtils.deleteRecursively(new File(UPLOAD_ROOT));

            Files.createDirectories(Paths.get(UPLOAD_ROOT));
        };
    }
}
