package net.javaguides.email_service.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import net.javaguides.common_lib.dto.ApiResponse;
import net.javaguides.common_lib.dto.order.OrderDTO;
import net.javaguides.common_lib.dto.order.OrderEvent;
import net.javaguides.common_lib.dto.order.OrderItemDTO;
import net.javaguides.email_service.dto.ProductStockResponse;
import org.apache.kafka.common.utils.Java;
import org.hibernate.query.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EmailService {
    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private ProductAPIClient productClient;
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    public void sendOrderConfirmationEmail(OrderEvent order) throws MessagingException, IOException {
        try {
            String template = loadTemplate("templates/email-template.html");

            List<OrderItemDTO> items = order.getOrderDTO().getOrderItems();
            StringBuilder orderItemsHtmlBuilder = new StringBuilder();
            BigDecimal amount = BigDecimal.valueOf(0);

            NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

            for (OrderItemDTO item:items){
                try {
                    ApiResponse<ProductStockResponse> productResponse = productClient.getProductById(item.getProductId()).getBody();

                    if(productResponse!=null && productResponse.getStatusCode()!=200){
                        logger.warn("Product with ID {} not found ",item.getProductId());
                        return;
                    }

                    BigDecimal totalPrice = productResponse.getData().getProduct().getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));

                    amount = amount.add(totalPrice);

                    String formattedUnitPrice = currencyFormatter.format(productResponse.getData().getProduct().getPrice());
                    String formattedTotalPrice = currencyFormatter.format(totalPrice);

                    orderItemsHtmlBuilder.append("<tr>")
                            .append("<td><img width='100' height='100' src='").append(productResponse.getData().getProduct().getImageUrl()).append("' alt='Product Image'/></td>")
                            .append("<td>").append(productResponse.getData().getProduct().getName()).append("</td>")
                            .append("<td>").append(item.getQuantity()).append("</td>")
                            .append("<td>").append(formattedUnitPrice).append("</td>")
                            .append("<td>").append(formattedTotalPrice).append("</td>")
                            .append("</tr>");

                }
                catch (Exception e){
                    logger.error("Error fetching product with ID {}:{}",item.getProductId(),e.getMessage());
                    continue;
                }
            }

            String orderItemsHtml = orderItemsHtmlBuilder.toString();
            String formattedGranTotal = currencyFormatter.format(amount);

            Map<String,String > variables = Map.of(
                    "customerName",order.getEmail(),
                    "orderId",order.getOrderDTO().getOrderId(),
                    "orderDate",order.getOrderDTO().getCreatedAt().toString(),
                    "orderItems",orderItemsHtml,
                    "grandTotal",formattedGranTotal,
                    "actionUrl","https://yourapp.com/orders/" + order.getOrderDTO().getOrderId()
            );

            String htmlContent = replacePlaceholders(template,variables);

            MimeMessage message = mailSender.createMimeMessage();

            MimeMessageHelper helper = new MimeMessageHelper(message,true,"UTF-8");
            helper.setTo(order.getEmail());
            helper.setSubject("Your Order Confirmation - " + order.getOrderDTO().getOrderId());
            helper.setText(htmlContent,true);


            mailSender.send(message);
            logger.info("Order confirmation email send to {}",order.getEmail());
        }catch (Exception e){
            logger.error("Error fetching product with ID {}:{}",order.getOrderDTO().getOrderId(),e.getMessage());
        throw  e;
        }
    }

    private String loadTemplate(String path) throws IOException{
        ClassPathResource resource = new ClassPathResource(path);
        StringBuilder contentBuilder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)
        )){
            String line;
            while ((line= reader.readLine())!=null){
                contentBuilder.append(line).append("\n");
            }
        }
        return contentBuilder.toString();
    }

    private String replacePlaceholders(String template,Map<String,String> variables){
        String result = template;

        for (Map.Entry<String,String> entry:variables.entrySet()){
            String placeholder = "{" + entry.getKey() + "}";
            result = result.replace(placeholder,entry.getValue());
        }
        return result;
    }

}