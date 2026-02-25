package health.tiro.examples.jxbrowser;

import health.tiro.swm.r4.SmartMessageHandler;
import health.tiro.formfiller.swing.*;
import health.tiro.formfiller.swing.jxbrowser.*;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;

import static ca.uhn.fhir.context.FhirContext.forR4Cached;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.Map;

public class CompleteExample {

    private static final String[][] TEMPLATES = {
            {"Operatieverslag RARP", "http://templates.tiro.health/templates/2630b8675c214707b1f86d1fbd4deb87"},
            {"Anesthesieverslag", "http://templates.tiro.health/templates/c6fa8202c02c447d8acad47bc60fb336"},
    };

    private static final Map<String, QuestionnaireResponse> savedResponses = new HashMap<>();
    private static String currentTemplateUrl = null;
    private static PractitionerRole selectedRole = null;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            // 1. Create the browser, handler, and form filler — these are long-lived
            EmbeddedBrowser browser = new JxBrowserAdapter(
                    JxBrowserConfig.builder().licenseKey(System.getProperty("jxbrowser.license.key")).build()
            );
            SmartMessageHandler handler = new SmartMessageHandler();
            FormFillerConfig config = FormFillerConfig.builder()
                    .targetUrl("http://localhost:8000")
                    .build();
            FormFiller filler = new FormFiller(config, browser, handler);

            // 2. Set up clinical context
            Patient patient = createPatient();
            PractitionerRole[] roles = createPractitionerRoles();
            selectedRole = roles[0];
            Encounter encounter = createEncounter(patient);

            // 3. Build the UI
            JFrame frame = new JFrame("EHR Workspace — Tiro.health Demo");
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setSize(1400, 900);
            frame.setLayout(new BorderLayout());

            JPanel headerBar = createHeaderBar(selectedRole);
            frame.add(headerBar, BorderLayout.NORTH);

            JScrollPane leftScroll = new JScrollPane(createPatientContextPanel(patient, encounter));
            leftScroll.setBorder(null);
            leftScroll.setPreferredSize(new Dimension(400, 0));

            JPanel rightPanel = createReportingPanel(filler, handler, patient, encounter, roles, headerBar);

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightPanel);
            splitPane.setDividerLocation(420);
            splitPane.setResizeWeight(0.0);
            frame.add(splitPane, BorderLayout.CENTER);

            // 4. Listen for form events — the filler stays open, ready for the next form
            filler.addFormFillerListener(new FormFillerListener() {
                @Override
                public void onFormSubmitted(IBaseResource response, IBaseResource outcome) {
                    if (currentTemplateUrl != null && response instanceof QuestionnaireResponse) {
                        savedResponses.put(currentTemplateUrl, (QuestionnaireResponse) response);
                    }
                    String json = forR4Cached().newJsonParser().setPrettyPrint(true).encodeResourceToString(response);
                    System.out.println("Form submitted:\n" + json);

                    // Show the narrative in a popup
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
                    System.out.println("Form closed by user");
                }
            });

            // 5. Dispose the filler when the window is closed
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    filler.dispose();
                    frame.dispose();
                }
            });

            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // 6. Load the first template automatically
            loadTemplate(handler, 0, patient, encounter);
        });
    }

    private static void loadTemplate(SmartMessageHandler handler, int index,
                                     Patient patient, Encounter encounter) {
        currentTemplateUrl = TEMPLATES[index][1];
        QuestionnaireResponse saved = savedResponses.get(currentTemplateUrl);
        handler.sendSdcDisplayQuestionnaireAsync(
                currentTemplateUrl, saved, patient, encounter, selectedRole, null
        ).exceptionally(e -> {
            System.err.println("Failed to display questionnaire: " + e.getMessage());
            return null;
        });
    }

    // --- Clinical context ---

    private static Patient createPatient() {
        Patient patient = new Patient();
        patient.setId("patient-001");
        patient.addName(new HumanName()
                .setFamily("Peeters")
                .addGiven("Marc")
                .setText("Peeters, Marc"));
        patient.setBirthDateElement(new DateType("1958-12-01"));
        patient.setGender(Enumerations.AdministrativeGender.MALE);
        patient.addIdentifier(new Identifier()
                .setSystem("http://example.org/patient-ids")
                .setValue("patient-001"));
        return patient;
    }

    private static PractitionerRole[] createPractitionerRoles() {
        PractitionerRole surgeon = new PractitionerRole();
        surgeon.setId("role-001");
        surgeon.setPractitioner(new Reference("Practitioner/practitioner-001")
                .setDisplay("dr. Van Damme"));
        surgeon.addCode(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://snomed.info/sct")
                .setCode("304292004")
                .setDisplay("Surgeon")));
        surgeon.addSpecialty(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://snomed.info/sct")
                .setCode("394609007")
                .setDisplay("General surgery")));

        PractitionerRole anesthesiologist = new PractitionerRole();
        anesthesiologist.setId("role-002");
        anesthesiologist.setPractitioner(new Reference("Practitioner/practitioner-002")
                .setDisplay("dr. Janssen"));
        anesthesiologist.addCode(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://snomed.info/sct")
                .setCode("88189002")
                .setDisplay("Anesthesiologist")));
        anesthesiologist.addSpecialty(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://snomed.info/sct")
                .setCode("394577000")
                .setDisplay("Anesthetics")));

        return new PractitionerRole[]{surgeon, anesthesiologist};
    }

    private static Encounter createEncounter(Patient patient) {
        Encounter encounter = new Encounter();
        encounter.setId("ENC-2026-00214");
        encounter.setStatus(Encounter.EncounterStatus.INPROGRESS);
        encounter.setClass_(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
                .setCode("IMP")
                .setDisplay("inpatient encounter"));
        encounter.addType(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://snomed.info/sct")
                .setCode("708173004")
                .setDisplay("Robotic assisted laparoscopic prostatectomy")));
        encounter.setSubject(new Reference("Patient/" + patient.getId())
                .setDisplay(patient.getNameFirstRep().getText()));
        encounter.setPriority(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActPriority")
                .setCode("EL")
                .setDisplay("elective")));
        encounter.addLocation().setLocation(new Reference().setDisplay("OR 4"));
        return encounter;
    }

    // --- UI components ---

    private static final String LOGGED_IN_LABEL_NAME = "loggedInLabel";

    private static JPanel createHeaderBar(PractitionerRole role) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE0E0E0)),
                BorderFactory.createEmptyBorder(10, 20, 10, 20)
        ));

        JLabel title = new JLabel("EHR Workspace");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel subtitle = new JLabel("  Tiro.health Demo");
        subtitle.setForeground(Color.GRAY);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(subtitle);

        JLabel practitionerLabel = new JLabel(loggedInText(role));
        practitionerLabel.setName(LOGGED_IN_LABEL_NAME);
        practitionerLabel.setFont(practitionerLabel.getFont().deriveFont(Font.PLAIN, 13f));
        practitionerLabel.setForeground(new Color(0x555555));

        header.add(left, BorderLayout.WEST);
        header.add(practitionerLabel, BorderLayout.EAST);
        return header;
    }

    private static String loggedInText(PractitionerRole role) {
        String name = role.getPractitioner().getDisplay();
        String roleDisplay = role.getCodeFirstRep().getCodingFirstRep().getDisplay();
        return "LOGGED IN  " + name + " (" + roleDisplay + ")";
    }

    private static void updateHeaderBar(JPanel headerBar, PractitionerRole role) {
        for (Component c : headerBar.getComponents()) {
            if (c instanceof JLabel && LOGGED_IN_LABEL_NAME.equals(c.getName())) {
                ((JLabel) c).setText(loggedInText(role));
                break;
            }
        }
    }

    private static JPanel createPatientContextPanel(Patient patient, Encounter encounter) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(0xF5F5F5));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel heading = new JLabel("Patient Context");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(heading);
        panel.add(Box.createVerticalStrut(15));

        // Patient card
        HumanName name = patient.getNameFirstRep();
        String dob = patient.getBirthDateElement().getValueAsString();
        String gender = patient.getGender().getDisplay();
        int age = Period.between(LocalDate.parse(dob), LocalDate.now()).getYears();

        panel.add(createCard("PATIENT",
                boldLabel(name.getFamily() + ", " + name.getGivenAsSingleString(), 15f),
                plainLabel("DOB " + dob + " \u2022 " + gender + " \u2022 " + age + " years")
        ));
        panel.add(Box.createVerticalStrut(12));

        // Current encounter
        String encounterType = encounter.getTypeFirstRep().getCodingFirstRep().getDisplay();
        String location = encounter.getLocationFirstRep().getLocation().getDisplay();
        String priority = encounter.getPriority().getCodingFirstRep().getDisplay();
        panel.add(createCard("CURRENT ENCOUNTER",
                plainLabel(encounterType),
                plainLabel(location + " \u2022 " + priority + " \u2022 Encounter " + encounter.getId())
        ));

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private static JPanel createReportingPanel(FormFiller filler, SmartMessageHandler handler,
                                                  Patient patient, Encounter encounter,
                                                  PractitionerRole[] roles, JPanel headerBar) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(0xF5F5F5));

        // Top bar with practitioner and template selectors
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0xE0E0E0)),
                BorderFactory.createEmptyBorder(12, 20, 12, 20)
        ));

        JLabel title = new JLabel("Reporting");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));

        JPanel selector = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        selector.setOpaque(false);

        // Practitioner selector
        JLabel practitionerLabel = new JLabel("PRACTITIONER");
        practitionerLabel.setFont(practitionerLabel.getFont().deriveFont(Font.PLAIN, 11f));
        practitionerLabel.setForeground(Color.GRAY);

        String[] practitionerNames = new String[roles.length];
        for (int i = 0; i < roles.length; i++)
            practitionerNames[i] = roles[i].getPractitioner().getDisplay();
        JComboBox<String> practitionerCombo = new JComboBox<>(practitionerNames);
        practitionerCombo.addActionListener(e -> {
            selectedRole = roles[practitionerCombo.getSelectedIndex()];
            updateHeaderBar(headerBar, selectedRole);
            handler.sendSdcConfigureContextAsync(patient, encounter, selectedRole, null);
        });

        // Template selector
        JLabel templateLabel = new JLabel("TEMPLATE");
        templateLabel.setFont(templateLabel.getFont().deriveFont(Font.PLAIN, 11f));
        templateLabel.setForeground(Color.GRAY);

        String[] templateNames = new String[TEMPLATES.length];
        for (int i = 0; i < TEMPLATES.length; i++) templateNames[i] = TEMPLATES[i][0];
        JComboBox<String> templateCombo = new JComboBox<>(templateNames);
        templateCombo.addActionListener(e ->
                loadTemplate(handler, templateCombo.getSelectedIndex(), patient, encounter)
        );

        selector.add(practitionerLabel);
        selector.add(practitionerCombo);
        selector.add(Box.createHorizontalStrut(12));
        selector.add(templateLabel);
        selector.add(templateCombo);
        topBar.add(title, BorderLayout.WEST);
        topBar.add(selector, BorderLayout.EAST);

        panel.add(topBar, BorderLayout.NORTH);
        panel.add(filler.getComponent(), BorderLayout.CENTER);
        return panel;
    }

    // --- UI helpers ---

    private static JPanel createCard(String title, JComponent... content) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE0E0E0)),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)
        ));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 11f));
        titleLabel.setForeground(new Color(0x888888));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(titleLabel);
        card.add(Box.createVerticalStrut(8));

        for (JComponent c : content) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(c);
            card.add(Box.createVerticalStrut(2));
        }
        return card;
    }

    private static JLabel boldLabel(String text, float size) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size));
        return label;
    }

    private static JLabel plainLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(0x555555));
        return label;
    }
}
