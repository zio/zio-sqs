const sidebars = {
  sidebar: [
    "index",
    "getting-started",
    {
      type: "category",
      label: "Reference",
      collapsed: false,
      items: [
        "reference/sqs-stream",
        "reference/sqs-stream-settings",
        "reference/producer",
        "reference/producer-settings",
        "reference/producer-event",
        "reference/serializer-and-utils"
      ]
    },
    "tutorials/build-a-queue-worker",
    {
      type: "category",
      label: "How-to Guides",
      collapsed: true,
      items: [
        "how-to/consume-messages-from-a-queue",
        "how-to/process-messages-at-least-once",
        "how-to/send-messages-to-a-queue",
        "how-to/send-messages-to-a-fifo-queue",
        "how-to/handle-send-failures",
        "how-to/use-a-custom-serializer",
        "how-to/test-against-a-local-sqs"
      ]
    }
  ]
};

module.exports = sidebars;
