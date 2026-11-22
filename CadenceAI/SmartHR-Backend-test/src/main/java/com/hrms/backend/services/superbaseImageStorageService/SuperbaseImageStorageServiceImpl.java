package com.hrms.backend.services.superbaseImageStorageService;

import com.hrms.backend.exceptions.BadApiRequestException;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

@Service
public class SuperbaseImageStorageServiceImpl implements SuperbaseImageStorageServiceInterface {

    // Values come from application.properties, which reads the SUPABASE_URL and
    // SUPABASE_ANON_KEY environment variables (see SETUP_INSTRUCTIONS.md).
    @Value("${supabase.url:}")
    private String supabaseUrl;

    @Value("${supabase.anon-key:}")
    private String anonKey;

    //all extension image allowed
    @Override
    public String uploadImage(MultipartFile image,String bucketName)  {
        if (supabaseUrl.isBlank() || anonKey.isBlank()) {
            throw new BadApiRequestException("Image storage is not configured on the server");
        }
        String fileName = UUID.randomUUID() + "-" + image.getOriginalFilename();
        String endpoint = supabaseUrl + "/storage/v1/object/" + bucketName + "/" + fileName;

        OkHttpClient client = new OkHttpClient();

        try {
            RequestBody body = RequestBody.create(
                    image.getBytes(),
                    MediaType.parse(Objects.requireNonNull(image.getContentType()))
            );

            Request request = new Request.Builder()
                    .url(endpoint)
                    .put(body)
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer " + anonKey)
                    .build();

            try (Response res = client.newCall(request).execute()) {
                if (!res.isSuccessful()) {
                    String error = res.body() != null ? res.body().string() : "Unknown error";
                    throw new BadApiRequestException("Image upload failed: " + error);
                }
                return supabaseUrl + "/storage/v1/object/public/" + bucketName + "/" + fileName;
            }

        } catch (IOException e) {
            throw new BadApiRequestException("Failed to upload image, try again later!!");
        }
    }
}

