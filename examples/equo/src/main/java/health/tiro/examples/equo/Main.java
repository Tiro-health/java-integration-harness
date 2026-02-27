package health.tiro.examples.equo;

import health.tiro.swm.r5.SmartMessageHandler;
import health.tiro.formfiller.swing.*;
import health.tiro.formfiller.swing.equo.*;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.*;

import static ca.uhn.fhir.context.FhirContext.forR5Cached;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // 1. Create a browser adapter
            EmbeddedBrowser browser = new EquoBrowserAdapter(
                    EquoBrowserConfig.builder().build()
            );

            // 2. Create the FHIR handler
            SmartMessageHandler handler = new SmartMessageHandler();

            // 3. Create the form filler
            FormFillerConfig config = FormFillerConfig.builder()
                    .sdcEndpointAddress("http://localhost:8000/fhir/r5")
                    .build();
            FormFiller filler = new FormFiller(config, browser, handler);

            // 4. Create the Swing frame
            JFrame frame = new JFrame("Form Filler (Equo)");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    filler.close();
                    frame.dispose();
                }
            });
            frame.setSize(1024, 1468);

            // Submit button
            JButton submitButton = new JButton("Submit");
            submitButton.addActionListener(e -> filler.requestSubmit());
            JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottomBar.add(submitButton);
            frame.add(bottomBar, BorderLayout.SOUTH);

            frame.add(filler.getComponent(), BorderLayout.CENTER);

            // 5. Listen for events
            filler.addFormFillerListener(new FormFillerListener() {
                @Override
                public void onFormSubmitted(IBaseResource response, IBaseResource outcome) {
                    String json = forR5Cached().newJsonParser().setPrettyPrint(true).encodeResourceToString(response);
                    System.out.println("Form submitted: " + json);

                    String narrative = null;
                    if (response instanceof DomainResource) {
                        Narrative text = ((DomainResource) response).getText();
                        if (text != null) narrative = text.getDivAsString();
                    }

                    if (narrative != null) {
                        JEditorPane htmlPane = new JEditorPane("text/html", narrative);
                        htmlPane.setEditable(false);
                        JScrollPane scrollPane = new JScrollPane(htmlPane);
                        scrollPane.setPreferredSize(new Dimension(600, 400));
                        JOptionPane.showMessageDialog(frame, scrollPane,
                                "Form Submitted", JOptionPane.PLAIN_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(frame,
                                "QuestionnaireResponse submitted (no narrative).",
                                "Form Submitted", JOptionPane.INFORMATION_MESSAGE);
                    }
                }

                @Override
                public void onCloseRequested() {
                    filler.close();
                    frame.dispose();
                }
            });

            frame.setVisible(true);

            // 6. Display a questionnaire (waits for handshake automatically)
            Patient patient = new Patient();
            patient.setId("test-123");
            patient.addName(new HumanName()
                    .setFamily("Tiro")
                    .addGiven("Marcus")
                    .addGiven("Tullius")
                    .setText("Marcus Tullius Tiro"));
            patient.setBirthDateElement(new DateType("0060-01-01"));
            patient.setGender(Enumerations.AdministrativeGender.MALE);
            patient.addIdentifier(new Identifier()
                    .setSystem("http://test.org/test/patient-ids")
                    .setValue("test-123"));

            handler.sendSdcDisplayQuestionnaireAsync(
                    "http://templates.tiro.health/templates/2630b8675c214707b1f86d1fbd4deb87",
                    null, patient, null, (Practitioner) null, null
            ).exceptionally(e -> {
                System.err.println("Failed to display questionnaire: " + e.getMessage());
                return null;
            });
        });
    }
}
